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
        // fetch the Maven settings with artifact caching proxy in a
        // tmp folder, and set MAVEN_SETTINGS env var to its absolute
        // location
        infra.withArtifactCachingProxy {
            repositoryOrigin = "https://repo." + (env.ARTIFACT_CACHING_PROXY_PROVIDER ?: 'azure') + ".jenkins.io"
            withEnv(["ARTIFACT_CACHING_PROXY_ORIGIN=$repositoryOrigin"]) {
                withCredentials([usernamePassword(credentialsId: 'artifact-caching-proxy-credentials',
                                 usernameVariable: 'ARTIFACT_CACHING_PROXY_USERNAME',
                                 passwordVariable: 'ARTIFACT_CACHING_PROXY_PASSWORD')]) {
                    infra.runWithMaven('java -jar target/extension-indexer-*-bin/extension-indexer-*.jar -adoc dist', '11')
                }
            }
        }
    }

    stage ('Publish') {
        // extension-indexer must not include directory name in their zip files
        sh 'cd dist && zip -r -1 -q ../extension-indexer.zip .'
        if(env.BRANCH_IS_PRIMARY && infra.isInfra()) {
            infra.publishReports(['extension-indexer.zip'])
        } else {
            // On branches and PR, archive the files (there is a
            // buildDiscarder to clean it up) so contributors will be
            // able to check changes
            archiveArtifacts artifacts: 'extension-indexer.zip'
        }
    }
}
