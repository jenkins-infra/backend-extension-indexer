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

def javaVersion = '21'

node('linux-amd64') {
    stage ('Prepare') {
        deleteDir()
        checkout scm
    }

    stage ('Build') {
        infra.runMaven(["clean", "verify"], javaVersion)
    }

    stage ('Generate') {
        // fetch the Maven settings with artifact caching proxy in a
        // tmp folder, and set MAVEN_SETTINGS env var to its absolute
        // location
        infra.withArtifactCachingProxy {
            infra.runWithMaven('java -jar target/extension-indexer-*-bin/extension-indexer-*.jar -adoc dist', javaVersion)
        }
    }

    stage ('Publish') {
        // extension-indexer must not include directory name in their zip files
        sh 'cd dist && zip -r -1 -q ../extension-indexer.zip .'
        archiveArtifacts artifacts: 'extension-indexer.zip'

        if (env.BRANCH_IS_PRIMARY && infra.isInfra()) {
            infra.publishReports(['extension-indexer.zip'])
        }
    }
}
