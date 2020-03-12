package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.model.*;
import com.capitalone.dashboard.repository.*;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SonarStaticAnalysisCollectorTask extends SonarCollectorTask {
    private static final Log LOG = LogFactory.getLog(SonarStaticAnalysisCollectorTask.class);
    private static final String collectorName = "Sonar";
    private final SonarStaticAnalysisCollectorRepository sonarStaticAnalysisCollectorRepository;
    private final SonarSettings sonarSettings;
    private final SonarClientSelector sonarClientSelector;
    private final ConfigurationRepository configurationRepository;

    @Autowired
    public SonarStaticAnalysisCollectorTask(TaskScheduler taskScheduler,
                                            SonarStaticAnalysisCollectorRepository sonarStaticAnalysisCollectorRepository,
                                            SonarProjectRepository sonarProjectRepository,
                                            CodeQualityRepository codeQualityRepository,
                                            SonarProfileRepostory sonarProfileRepostory,
                                            SonarSettings sonarSettings,
                                            SonarClientSelector sonarClientSelector,
                                            ConfigurationRepository configurationRepository,
                                            ComponentRepository dbComponentRepository) {
        super(taskScheduler, collectorName, sonarProjectRepository, codeQualityRepository, sonarProfileRepostory, dbComponentRepository);
        this.sonarStaticAnalysisCollectorRepository = sonarStaticAnalysisCollectorRepository;
        this.sonarSettings = sonarSettings;
        this.sonarClientSelector = sonarClientSelector;
        this.configurationRepository = configurationRepository;
    }

    @Override
    public SonarStaticAnalysisCollector getCollector() {

        Configuration config = configurationRepository.findByCollectorName(collectorName);
        // Only use Admin Page server configuration when available
        // otherwise use properties file server configuration
        if (config != null) {
            config.decryptOrEncrptInfo();
            // To clear the username and password from existing run and
            // pick the latest
            sonarSettings.getServers().clear();
            sonarSettings.getUsernames().clear();
            sonarSettings.getPasswords().clear();
            for (Map<String, String> sonarServer : config.getInfo()) {
                sonarSettings.getServers().add(sonarServer.get("url"));
                sonarSettings.getUsernames().add(sonarServer.get("userName"));
                sonarSettings.getPasswords().add(sonarServer.get("password"));
            }
        }

        return SonarStaticAnalysisCollector.prototype(sonarSettings.getServers(),  sonarSettings.getNiceNames());
    }


    @Override
    public String getCron() {
        return sonarSettings.getCron();
    }

    @Override
    public SonarStaticAnalysisCollectorRepository getCollectorRepository() {
        return sonarStaticAnalysisCollectorRepository;
    }

    @Override
    public void collect(Collector collector) {
        SonarStaticAnalysisCollector sonarStaticAnalysisCollector = (SonarStaticAnalysisCollector) collector;
        long start = System.currentTimeMillis();

        Set<ObjectId> udId = new HashSet<>();
        udId.add(collector.getId());
        List<SonarProject> existingProjects = sonarProjectRepository.findByCollectorIdIn(udId);
        List<SonarProject> latestProjects = new ArrayList<>();
        clean(sonarStaticAnalysisCollector, existingProjects, CollectorType.CodeQuality);

        if (!CollectionUtils.isEmpty(sonarStaticAnalysisCollector.getSonarServers())) {

            for (int i = 0; i < sonarStaticAnalysisCollector.getSonarServers().size(); i++) {

                String instanceUrl = sonarStaticAnalysisCollector.getSonarServers().get(i);
                logBanner(instanceUrl);

                Double version = sonarClientSelector.getSonarVersion(instanceUrl);
                SonarClient sonarClient = sonarClientSelector.getSonarClient(version);

                String username = getFromListSafely(sonarSettings.getUsernames(), i);
                String password = getFromListSafely(sonarSettings.getPasswords(), i);
                String token = getFromListSafely(sonarSettings.getTokens(), i);
                sonarClient.setServerCredentials(username, password, token);

                List<SonarProject> projects = sonarClient.getProjects(instanceUrl);
                latestProjects.addAll(projects);

                int projSize = CollectionUtils.size(projects);
                log("Fetched projects   " + projSize, start);

                addNewProjects(projects, existingProjects, collector);

                refreshData(enabledProjects(collector, instanceUrl), sonarClient);

                // Changelog apis do not exist for sonarqube versions under version 5.0
                if (version >= 5.0) {
                  try {
                     fetchQualityProfileConfigChanges(collector,instanceUrl,sonarClient);
                   } catch (Exception e) {
                     LOG.error(e);
                    }
                }

                log("Finished", start);
            }
        }
        deleteUnwantedJobs(latestProjects, existingProjects, collector);
    }

    private void deleteUnwantedJobs(List<SonarProject> latestProjects, List<SonarProject> existingProjects, Collector collector) {
        List<SonarProject> deleteJobList = new ArrayList<>();

        // First delete collector items that are not supposed to be collected anymore because the servers have moved(?)
        for (SonarProject job : existingProjects) {
            if (job.isPushed()) continue; // do not delete jobs that are being pushed via API
            if (!((SonarStaticAnalysisCollector) collector).getSonarServers().contains(job.getInstanceUrl()) ||
                    (!job.getCollectorId().equals(collector.getId())) ||
                    (!latestProjects.contains(job))) {
                if(!job.isEnabled()) {
                    LOG.debug("drop deleted sonar project which is disabled "+job.getProjectName());
                    deleteJobList.add(job);
                } else {
                    LOG.debug("drop deleted sonar project which is enabled "+job.getProjectName());
                    deleteEnabledJobFromComponents(job, CollectorType.CodeQuality);

                    // other collectors also delete the widget but not here
                    // should not remove the code analysis widget
                    // because it is shared by other collectors

                    deleteJobList.add(job);
                }
            }
        }
        if (!CollectionUtils.isEmpty(deleteJobList)) {
            sonarProjectRepository.delete(deleteJobList);
        }
    }

    private void refreshData(List<SonarProject> sonarProjects, SonarClient sonarClient) {
        long start = System.currentTimeMillis();
        int count = 0;

        for (SonarProject project : sonarProjects) {
            CodeQuality codeQuality = sonarClient.currentStaticCodeQuality(project);
            if (codeQuality != null && isNewQualityData(project, codeQuality)) {
                project.setLastUpdated(System.currentTimeMillis());
                sonarProjectRepository.save(project);
                codeQuality.setCollectorItemId(project.getId());
                codeQualityRepository.save(codeQuality);
                count++;
            }
        }
        log("Updated", start, count);
    }

    protected String getNiceName(SonarProject project, Collector collector){
        SonarStaticAnalysisCollector sonarStaticAnalysisCollector = (SonarStaticAnalysisCollector) collector;
        if (org.springframework.util.CollectionUtils.isEmpty(sonarStaticAnalysisCollector.getSonarServers())) return "";
        List<String> servers = sonarStaticAnalysisCollector.getSonarServers();
        List<String> niceNames = sonarStaticAnalysisCollector.getNiceNames();
        if (org.springframework.util.CollectionUtils.isEmpty(niceNames)) return "";
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).equalsIgnoreCase(project.getInstanceUrl()) && (niceNames.size() > i)) {
                return niceNames.get(i);
            }
        }
        return "";

    }
}
