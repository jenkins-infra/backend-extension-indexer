#!groovy
// not really

properties([
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
        withEnv([
                "PATH+MVN=${tool 'mvn'}/bin",
                "JAVA_HOME=${tool 'jdk8'}",
                "PATH+JAVA=${tool 'jdk8'}/bin"
        ]) {
            sh 'mvn -B -e clean verify'
        }
    }

    stage ('Generate') {
        withEnv([
                "PATH+MVN=${tool 'mvn'}/bin",
                "JAVA_HOME=${tool 'jdk8'}",
                "PATH+JAVA=${tool 'jdk8'}/bin"
        ]) {
            sh 'java -jar target/extension-indexer-*-bin/extension-indexer-*.jar -adoc dist'
        }
    }

    stage('Archive') {
        dir ('dist') {
            archiveArtifacts '**'
        }
    }
}
