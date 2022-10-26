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

node('linux-amd64') {
    stage ('Prepare') {
        deleteDir()
        checkout scm
    }

    stage ('Build') {
        infra.runMaven(["clean", "verify"], '11')
    }

    stage ('Generate') {
        if (infra.isRunningOnJenkinsInfra()) {
            infra.retrieveMavenSettingsFile( "${pwd}/maven-settings.xml")
        }
        infra.runWithMaven('java -jar target/extension-indexer-*-bin/extension-indexer-*.jar -adoc dist', '11')
    }

    stage ('Publish') {
        zip dir: './dist', glob: '**', zipFile: 'extension-indexer.zip'
        if(env.BRANCH_IS_PRIMARY) {
            infra.publishReports(['extension-indexer.zip'])
        } else {
            // On branches and PR, archive the files (there is a buildDiscarder to clean it up) so conributors will be able to check changes
            archiveArtifacts artifacts: 'extension-indexer.zip'
        }
    }
}
