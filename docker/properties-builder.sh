#!/bin/bash

if [ "$SKIP_PROPERTIES_BUILDER" = true ]; then
  echo "Skipping properties builder"
  echo "`cat $PROP_FILE`"
  exit 0
fi

cat > $PROP_FILE <<EOF
dbname=${MONGODB_DATABASE:-dashboarddb}
dbhost=${MONGODB_HOST:-db}
dbport=${MONGODB_PORT:-27017}
dbusername=${MONGODB_USERNAME:-dashboarduser}
dbpassword=${MONGODB_PASSWORD:-dbpassword}

sonar.cron=${SONAR_CRON:-0 */5 * * * *}

sonar.servers[0]=${SONAR_URL:-http://localhost:9000}

sonar.usernames[0]=$SONAR_USERNAME
sonar.passwords[0]=$SONAR_PASSWORD
sonar.tokens[0]=$SONAR_TOKEN

#Sonar Metrics
sonar.staticMetrics63andAbove=${SONAR_STATIC_METRICS:-ncloc,violations,new_vulnerabilities,critical_violations,major_violations,blocker_violations,tests,test_success_density,test_errors,test_failures,coverage,line_coverage,sqale_index,alert_status,quality_gate_details}
sonar.securityMetrics63andAbove=${SONAR_SECURITY_METRICS:-vulnerabilities,new_vulnerabilities}

#Sonar Version - see above for semantics between version/metrics
sonar.versions[0]=${SONAR_VERSION}

EOF

echo "

===========================================
Properties file created `date`:  $PROP_FILE
Note: passwords hidden
===========================================
`cat $PROP_FILE |egrep -vi password`
"

exit 0
