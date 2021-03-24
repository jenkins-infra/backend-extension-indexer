#!groovy
// not really

properties([
        disableConcurrentBuilds(),
        buildDiscarder(logRotator(numToKeepStr: '5')),
        pipelineTriggers([
                // run every Sunday
                cron('H H * * 0')
        ])
])

node('highmem') {
    stage ('Prepare') {
        deleteDir()
        checkout scm
    }

    stage ('Build') {
        infra.runMaven(["clean", "verify"])
    }

    stage ('Generate') {
        if (infra.isRunningOnJenkinsInfra()) {
            infra.retrieveMavenSettingsFile( "${pwd}/maven-settings.xml")
        }
        infra.runWithMaven('java -jar target/extension-indexer-*-bin/extension-indexer-*.jar -adoc dist')
    }

    stage('Archive') {
        dir ('dist') {
            archiveArtifacts '**'
        }
    }
}
