@Library('github.com/redhat-ipaas/ipaas-pipeline-library@master')
def mavenVersion='3.3.9'

def date = new java.text.SimpleDateFormat("yyyyMMddHHmm").format(new Date())
def versionSuffix = "${date}-${env.BUILD_NUMBER}"
def version = "1.0-${versionSuffix}"

openshiftTemplate {
    mavenNode(mavenImage: "maven:${mavenVersion}") {

        stage 'Build'
        container(name: 'maven') {
            pom = readMavenPom(file: 'pom.xml')
            version = pom.version.replaceAll("SNAPSHOT", "versionSuffix")

            sh "mvn clean install"
        }

        stage 'System Tests'
        runSystemTests(component: 'ipaas-rest', version: "${version}")

        stage 'Deploy'
        updateComponent(component: 'ipaas-rest', version: "${version}", namespace: 'ipaas-staging')
    }
}
