package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.model.*;
import com.capitalone.dashboard.repository.*;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.bson.types.ObjectId;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.springframework.scheduling.TaskScheduler;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

public abstract class SuperSonarCollectorTask<T extends Collector> extends CollectorTask<T> {
    protected final SonarProjectRepository sonarProjectRepository;
    protected final CodeQualityRepository codeQualityRepository;
    protected final SonarProfileRepostory sonarProfileRepostory;
    protected final ComponentRepository dbComponentRepository;

    public SuperSonarCollectorTask(TaskScheduler taskScheduler, String collectorName, SonarProjectRepository sonarProjectRepository, CodeQualityRepository codeQualityRepository, SonarProfileRepostory sonarProfileRepostory, ComponentRepository dbComponentRepository) {
        super(taskScheduler, collectorName);
        this.sonarProjectRepository = sonarProjectRepository;
        this.codeQualityRepository = codeQualityRepository;
        this.sonarProfileRepostory = sonarProfileRepostory;
        this.dbComponentRepository = dbComponentRepository;
    }

    protected String getFromListSafely(List<String> ls, int index){
        if(CollectionUtils.isEmpty(ls)) {
            return null;
        } else if (ls.size() > index){
            return ls.get(index);
        }
        return null;
    }

    /**
	 * Clean up unused sonar collector items
	 *
	 * @param collectorType
     * @param collector
	 *            the {@link SonarCollector}
	 */
    protected void clean(T collector, List<SonarProject> existingProjects, CollectorType collectorType) {
        // extract unique collector item IDs from components
        // (in this context collector_items are sonar projects)
        Set<ObjectId> uniqueIDs = StreamSupport.stream(dbComponentRepository.findAll().spliterator(),false)
            .filter( comp -> comp.getCollectorItems() != null && !comp.getCollectorItems().isEmpty())
            .map(comp -> comp.getCollectorItems().get(collectorType))
            // keep nonNull List<CollectorItem>
            .filter(Objects::nonNull)
            // merge all lists (flatten) into a stream
            .flatMap(List::stream)
            // keep nonNull CollectorItems
            .filter(ci -> ci != null && ci.getCollectorId().equals(collector.getId()))
            .map(CollectorItem::getId)
            .collect(Collectors.toSet());

        List<SonarProject> stateChangeJobList = new ArrayList<>();

        for (SonarProject job : existingProjects) {
            // collect the jobs that need to change state : enabled vs disabled.
            if ((job.isEnabled() && !uniqueIDs.contains(job.getId())) ||  // if it was enabled but not on a dashboard
                    (!job.isEnabled() && uniqueIDs.contains(job.getId()))) { // OR it was disabled and now on a dashboard
                job.setEnabled(uniqueIDs.contains(job.getId()));
                stateChangeJobList.add(job);
            }
        }
        if (!CollectionUtils.isEmpty(stateChangeJobList)) {
            sonarProjectRepository.save(stateChangeJobList);
        }
    }

    protected void deleteEnabledJobFromComponents(SonarProject job, CollectorType collectorType) {
        // CollectorItem should be removed from components and dashboards first
        // then the CollectorItem (sonar proj in this case) can be deleted

        List<Component> comps =
                dbComponentRepository
            .findByCollectorTypeAndItemIdIn(collectorType, Collections.singletonList(job.getId()));

        for (Component c: comps) {
            c.getCollectorItems().get(collectorType).removeIf(collectorItem -> collectorItem.getId().equals(job.getId()));
            if(CollectionUtils.isEmpty(c.getCollectorItems().get(collectorType))){
                c.getCollectorItems().remove(collectorType);
            }
        }
        dbComponentRepository.save(comps);
    }

    protected void fetchQualityProfileConfigChanges(T collector, String instanceUrl, SonarClient sonarClient) throws org.json.simple.parser.ParseException{
    	JSONArray qualityProfiles = sonarClient.getQualityProfiles(instanceUrl);
    	JSONArray sonarProfileConfigurationChanges = new JSONArray();

    	for (Object qualityProfile : qualityProfiles ) {
    		JSONObject qualityProfileJson = (JSONObject) qualityProfile;
    		String qualityProfileKey = (String)qualityProfileJson.get("key");

    		List<String> sonarProjects = sonarClient.retrieveProfileAndProjectAssociation(instanceUrl,qualityProfileKey);
    		if (sonarProjects != null){
    			sonarProfileConfigurationChanges = sonarClient.getQualityProfileConfigurationChanges(instanceUrl,qualityProfileKey);
    			addNewConfigurationChanges(collector,sonarProfileConfigurationChanges);
    		}
    	}
    }

    private void addNewConfigurationChanges(T collector, JSONArray sonarProfileConfigurationChanges){
    	ArrayList<CollectorItemConfigHistory> profileConfigChanges = new ArrayList<>();

    	for (Object configChange : sonarProfileConfigurationChanges) {
    		JSONObject configChangeJson = (JSONObject) configChange;
    		CollectorItemConfigHistory profileConfigChange = new CollectorItemConfigHistory();
    		Map<String,Object> changeMap = new HashMap<>();

    		profileConfigChange.setCollectorItemId(collector.getId());
    		profileConfigChange.setUserName((String) configChangeJson.get("authorName"));
    		profileConfigChange.setUserID((String) configChangeJson.get("authorLogin") );
    		changeMap.put("event", configChangeJson);

    		profileConfigChange.setChangeMap(changeMap);

    		ConfigHistOperationType operation = determineConfigChangeOperationType((String)configChangeJson.get("action"));
    		profileConfigChange.setOperation(operation);


    		long timestamp = convertToTimestamp((String) configChangeJson.get("date"));
    		profileConfigChange.setTimestamp(timestamp);

    		if (isNewConfig(collector.getId(),(String) configChangeJson.get("authorLogin"),operation,timestamp)) {
    			profileConfigChanges.add(profileConfigChange);
    		}
    	}
    	sonarProfileRepostory.save(profileConfigChanges);
    }

    private Boolean isNewConfig(ObjectId collectorId, String authorLogin, ConfigHistOperationType operation, long timestamp) {
    	List<CollectorItemConfigHistory> storedConfigs = sonarProfileRepostory.findProfileConfigChanges(collectorId, authorLogin,operation,timestamp);
    	return storedConfigs.isEmpty();
    }

    protected List<SonarProject> enabledProjects(T collector, String instanceUrl) {
        return sonarProjectRepository.findEnabledProjects(collector.getId(), instanceUrl);
    }

    protected void addNewProjects(List<SonarProject> projects, List<SonarProject> existingProjects, T collector) {
        long start = System.currentTimeMillis();
        int count = 0;
        List<SonarProject> newProjects = new ArrayList<>();
        List<SonarProject> updateProjects = new ArrayList<>();
        for (SonarProject project : projects) {
            String niceName = getNiceName(project,collector);
            if (!existingProjects.contains(project)) {
                project.setCollectorId(collector.getId());
                project.setEnabled(false);
                project.setDescription(project.getProjectName());
                project.setNiceName(niceName);
                newProjects.add(project);
                count++;
            }else{
                if(CollectionUtils.isNotEmpty(existingProjects)){
                    int[] indexes = IntStream.range(0,existingProjects.size()).filter(i-> existingProjects.get(i).equals(project)).toArray();
                    for (int index :indexes) {
                        SonarProject s = existingProjects.get(index);
                        s.setProjectId(project.getProjectId());
                        if(StringUtils.isEmpty(s.getNiceName())){
                            s.setNiceName(niceName);
                        }
                        updateProjects.add(s);
                    }
                }
            }
        }
        //save all in one shot
        if (!CollectionUtils.isEmpty(newProjects)) {
            sonarProjectRepository.save(newProjects);
        }
        if (!CollectionUtils.isEmpty(updateProjects)) {
            sonarProjectRepository.save(updateProjects);
        }
        log("New projects", start, count);
    }

    protected abstract String getNiceName(SonarProject project, T sonarCollector);

    @SuppressWarnings("unused")
	private boolean isNewProject(SonarCollector collector, SonarProject application) {
        return sonarProjectRepository.findSonarProject(
                collector.getId(), application.getInstanceUrl(), application.getProjectId()) == null;
    }

    protected boolean isNewQualityData(SonarProject project, CodeQuality codeQuality) {
        return codeQualityRepository.findByCollectorItemIdAndTimestamp(
                project.getId(), codeQuality.getTimestamp()) == null;
    }

    private long convertToTimestamp(String date) {

    	DateTimeFormatter formatter = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    	DateTime dt = formatter.parseDateTime(date);

        return new DateTime(dt).getMillis();
    }

    private ConfigHistOperationType determineConfigChangeOperationType(String changeAction){
    	switch (changeAction) {

	    	case "DEACTIVATED":
	    		return ConfigHistOperationType.DELETED;

	    	case "ACTIVATED":
	    		return ConfigHistOperationType.CREATED;
	    	default:
	    		return ConfigHistOperationType.CHANGED;
    	}
    }
}
