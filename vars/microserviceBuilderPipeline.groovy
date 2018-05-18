#!groovy
// Copyright (c) IBM 2017

/*------------------------
  Typical usage:
  @Library('MicroserviceBuilder') _
  microserviceBuilderPipeline {
    image = 'microservice-test'
  }

  The following parameters may also be specified. Their defaults are shown below.
  These are the names of images to be downloaded from https://hub.docker.com/.

    mavenImage = 'maven:3.5.2-jdk-8'
    dockerImage = 'docker'
    kubectlImage = 'ibmcom/k8s-kubectl:v1.8.3'
    helmImage = 'ibmcom/k8s-helm:v2.6.0'

  You can also specify:

    mvnCommands = 'clean package'
    build = 'true' - any value other than 'true' == false
    deploy = 'true' - any value other than 'true' == false
    test = 'true' - `mvn verify` is run if this value is `true` and a pom.xml exists
    debug = 'false' - namespaces created during tests are deleted unless this value is set to 'true'
    deployBranch = 'master' - only builds from this branch are deployed
    chartFolder = 'chart' - folder containing helm deployment chart
    manifestFolder = 'manifests' - folder containing kubectl deployment manifests
    namespace = 'targetNamespace' - deploys into Kubernetes targetNamespace.
      Default is to deploy into Jenkins' namespace.
    libertyLicenseJarName - override for Pipeline.LibertyLicenseJar.Name

-------------------------*/

import com.cloudbees.groovy.cps.NonCPS
import java.io.File
import java.util.UUID
import static java.util.UUID.randomUUID
import groovy.json.JsonOutput;
import groovy.json.JsonSlurperClassic;


def call(body) {
  def config = [:]
  // Parameter expansion works after the call to body() below.
  // See https://jenkins.io/doc/book/pipeline/shared-libraries/ 'Defining a more structured DSL'
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  print "microserviceBuilderPipeline : config = ${config}"
  
  // temp fix for stuck slaves
  def uuid = randomUUID() as String
  def label = uuid.take(8)

  def image = config.image
  def maven = (config.mavenImage == null) ? 'maven:3.5.2-jdk-8' : config.mavenImage
  def docker = (config.dockerImage == null) ? 'ibmcom/docker:17.10' : config.dockerImage
  def kubectl = (config.kubectlImage == null) ? 'ibmcom/k8s-kubectl:v1.8.3' : config.kubectlImage
  def helm = (config.helmImage == null) ? 'xianms/k8s-helm:v2.7.2' : config.helmImage
  def mvnCommands = (config.mvnCommands == null) ? 'clean package' : config.mvnCommands
  def registry = System.getenv("REGISTRY").trim()
  if (registry && !registry.endsWith('/')) registry = "${registry}/"
  def registrySecret = System.getenv("REGISTRY_SECRET").trim()
  def build = (config.build ?: System.getenv ("BUILD")).toBoolean()
  def deploy = (config.deploy ?: System.getenv ("DEPLOY")).toBoolean()
  def namespace = config.namespace ?: (System.getenv("NAMESPACE") ?: "").trim()

  // these options were all added later. Helm chart may not have the associated properties set.
  def test = (config.test ?: (System.getenv ("TEST") ?: "false").trim()).toLowerCase() == 'true'
  def debug = (config.debug ?: (System.getenv ("DEBUG") ?: "false").trim()).toLowerCase() == 'true'
  def deployBranch = config.deployBranch ?: ((System.getenv("DEFAULT_DEPLOY_BRANCH") ?: "").trim() ?: 'master')
  // will need to check later if user provided chartFolder location
  def userSpecifiedChartFolder = config.chartFolder
  def chartFolder = userSpecifiedChartFolder ?: ((System.getenv("CHART_FOLDER") ?: "").trim() ?: 'chart')
  def manifestFolder = config.manifestFolder ?: ((System.getenv("MANIFEST_FOLDER") ?: "").trim() ?: 'manifests')
  def libertyLicenseJarBaseUrl = (System.getenv("LIBERTY_LICENSE_JAR_BASE_URL") ?: "").trim()
  def libertyLicenseJarName = config.libertyLicenseJarName ?: (System.getenv("LIBERTY_LICENSE_JAR_NAME") ?: "").trim()
  def alwaysPullImage = (System.getenv("ALWAYS_PULL_IMAGE") == null) ? true : System.getenv("ALWAYS_PULL_IMAGE").toBoolean()
  def mavenSettingsConfigMap = System.getenv("MAVEN_SETTINGS_CONFIG_MAP")?.trim() 

  print "microserviceBuilderPipeline: registry=${registry} registrySecret=${registrySecret} build=${build} \
  deploy=${deploy} deployBranch=${deployBranch} test=${test} debug=${debug} namespace=${namespace} \
  chartFolder=${chartFolder} manifestFolder=${manifestFolder} alwaysPullImage=${alwaysPullImage}"

  // We won't be able to get hold of registrySecret if Jenkins is running in a non-default namespace that is not the deployment namespace.
  // In that case we'll need the registrySecret to have been ported over, perhaps during pipeline install.

  // Only mount registry secret if it's present
  def volumes = [ hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock') ]
  if (registrySecret) {
    volumes += secretVolume(secretName: registrySecret, mountPath: '/msb_reg_sec')
  }
  if (mavenSettingsConfigMap) {
    volumes += configMapVolume(configMapName: mavenSettingsConfigMap, mountPath: '/msb_mvn_cfg')
  }
  print "microserviceBuilderPipeline: volumes = ${volumes}"

  podTemplate(
    label: "msbPod-${label}",
    inheritFrom: 'default',
    containers: [
      containerTemplate(name: 'maven', image: maven, ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'docker', image: docker, command: 'cat', ttyEnabled: true,
        envVars: [
          containerEnvVar(key: 'DOCKER_API_VERSION', value: '1.23.0')
        ]),
      containerTemplate(name: 'kubectl', image: kubectl, ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'helm', image: helm, ttyEnabled: true, command: 'cat'),
    ],
    volumes: volumes
  ) {
    node("msbPod-${label}") {
      def gitCommit

      stage ('Extract') {
        checkout scm
        gitCommit = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
        echo "checked out git commit ${gitCommit}"
      }

      def imageTag = null
      if (build) {
        if (fileExists('pom.xml')) {
          stage ('Maven Build') {
            container ('maven') {
              def mvnCommand = "mvn -B"
              if (mavenSettingsConfigMap) {
                mvnCommand += " --settings /msb_mvn_cfg/settings.xml"
              }
              mvnCommand += " ${mvnCommands}"
              sh mvnCommand
            }
          }
        }
        if (fileExists('Dockerfile')) {
          stage ('Docker Build') {
            container ('docker') {
              imageTag = gitCommit
              def buildCommand = "docker build -t ${image}:${imageTag} "
              buildCommand += "--label org.label-schema.schema-version=\"1.0\" "
              def scmUrl = scm.getUserRemoteConfigs()[0].getUrl()
              buildCommand += "--label org.label-schema.vcs-url=\"${scmUrl}\" "
              buildCommand += "--label org.label-schema.vcs-ref=\"${gitCommit}\" "  
              buildCommand += "--label org.label-schema.name=\"${image}\" "
              def buildDate = sh(returnStdout: true, script: "date -Iseconds").trim()
              buildCommand += "--label org.label-schema.build-date=\"${buildDate}\" "
              if (alwaysPullImage) {
                buildCommand += " --pull=true "
              }
              if (libertyLicenseJarBaseUrl) {
                if (readFile('Dockerfile').contains('LICENSE_JAR_URL')) {
                  buildCommand += " --build-arg LICENSE_JAR_URL=" + libertyLicenseJarBaseUrl
                  if (!libertyLicenseJarBaseUrl.endsWith("/")) {
                    buildCommand += "/"
                  }
                  buildCommand += libertyLicenseJarName
                }
              }
              buildCommand += " ."
              if (registrySecret) {
                sh "ln -s /msb_reg_sec/.dockercfg /home/jenkins/.dockercfg"
                sh "mkdir /home/jenkins/.docker"
                sh "ln -s /msb_reg_sec/.dockerconfigjson /home/jenkins/.docker/config.json"
              }
              sh buildCommand
              if (registry) {
                sh "docker tag ${image}:${imageTag} ${registry}${image}:${imageTag}"
                sh "docker push ${registry}${image}:${imageTag}"
              }
            }
          }
        }
      }

      def realChartFolder = null
      if (fileExists(chartFolder)) {
        // find the likely chartFolder location
        realChartFolder = getChartFolder(userSpecifiedChartFolder, chartFolder)
        def yamlContent = "image:"
        yamlContent += "\n  repository: ${registry}${image}"
        if (imageTag) yamlContent += "\n  tag: \\\"${imageTag}\\\""
        sh "echo \"${yamlContent}\" > pipeline.yaml"
      } else if (fileExists(manifestFolder)){
        sh "find ${manifestFolder} -type f | xargs sed -i 's|\\(image:\\s*\\)${image}:latest|\\1${registry}${image}:latest|g'"
        sh "find ${manifestFolder} -type f | xargs sed -i 's|\\(image:\\s*\\)${registry}${image}:latest|\\1${registry}${image}:${gitCommit}|g'"
      }

      if (test && fileExists('pom.xml') && realChartFolder != null && fileExists(realChartFolder)) {
        stage ('Verify') {
          testNamespace = "testns-${env.BUILD_ID}-" + UUID.randomUUID()
          print "testing against namespace " + testNamespace
          String tempHelmRelease = (image + "-" + testNamespace)
          // Name cannot end in '-' or be longer than 53 chars
          while (tempHelmRelease.endsWith('-') || tempHelmRelease.length() > 53) tempHelmRelease = tempHelmRelease.substring(0,tempHelmRelease.length()-1)
          container ('kubectl') {
            sh "kubectl create namespace ${testNamespace}"
            sh "kubectl label namespace ${testNamespace} test=true"
            if (registrySecret) {
              giveRegistryAccessToNamespace (testNamespace, registrySecret)
            }
          }
          
          container ('helm') {
            sh "bx plugin install /icp-linux-amd64 -f"
            sh "bx pr login -a https://9.110.71.77:8443 --skip-ssl-validation   -u admin -p admin  -c id-mycluster-account"
            sh "bx pr cluster-config mycluster"
            sh "/helm init --client-only --skip-refresh"
            def deployCommand = "/helm install ${realChartFolder} --tls --wait --set test=true --values pipeline.yaml --namespace ${testNamespace} --name ${tempHelmRelease}"
            if (fileExists("chart/overrides.yaml")) {
              deployCommand += " --values chart/overrides.yaml"
            }
            sh deployCommand
          }

          container ('maven') {
            try {
              def mvnCommand = "mvn -B -Dnamespace.use.existing=${testNamespace} -Denv.init.enabled=false"
              if (mavenSettingsConfigMap) {
                mvnCommand += " --settings /msb_mvn_cfg/settings.xml"
              }
              mvnCommand += " verify"
              sh mvnCommand
            } finally {
              step([$class: 'JUnitResultArchiver', allowEmptyResults: true, testResults: '**/target/failsafe-reports/*.xml'])
              step([$class: 'ArtifactArchiver', artifacts: '**/target/failsafe-reports/*.txt', allowEmptyArchive: true])
              if (!debug) {
                container ('kubectl') {
                  sh "kubectl delete namespace ${testNamespace}"
                  if (fileExists(realChartFolder)) {
                    container ('helm') {
                      sh "/helm delete ${tempHelmRelease} --tls --purge"
                    }
                  }
                }
              }
            }
          }
        }
      }

      if (deploy && env.BRANCH_NAME == deployBranch) {
        stage ('Deploy') {
          deployProject (realChartFolder, registry, image, imageTag, namespace, manifestFolder)
        }
      }
    }
  }
}

def deployProject (String chartFolder, String registry, String image, String imageTag, String namespace, String manifestFolder) {
  clearTemplateNames()
  if (chartFolder != null && fileExists(chartFolder)) {
    container ('helm') {
      sh "bx plugin install /icp-linux-amd64 -f"
      sh "bx pr login -a https://9.110.71.77:8443 --skip-ssl-validation   -u admin -p admin  -c id-mycluster-account"
      sh "bx pr cluster-config mycluster"
      sh "/helm init --client-only --skip-refresh"
      def deployCommand = "/helm upgrade --tls --install --wait --values pipeline.yaml"
      if (fileExists("chart/overrides.yaml")) {
        deployCommand += " --values chart/overrides.yaml"
      }
      if (namespace) deployCommand += " --namespace ${namespace}"
      def releaseName = (env.BRANCH_NAME == "master") ? "${image}" : "${image}-${env.BRANCH_NAME}"
      deployCommand += " ${releaseName} ${chartFolder}"
      sh deployCommand
    }
  } else if (fileExists(manifestFolder)) {
    container ('kubectl') {
      def deployCommand = "kubectl apply -f ${manifestFolder}"
      if (namespace) deployCommand += " --namespace ${namespace}"
      sh deployCommand
    }
  }
}

/*
  We have a (temporary) namespace that we want to grant ICP registry access to.
  String namespace: target namespace

  1. Port registrySecret into a temporary namespace
  2. Modify 'default' serviceaccount to use ported registrySecret.
*/

def giveRegistryAccessToNamespace (String namespace, String registrySecret) {
  sh "kubectl get secret ${registrySecret} -o json | sed 's/\"namespace\":.*\$/\"namespace\": \"${namespace}\",/g' | kubectl create -f -"
  sh "kubectl patch serviceaccount default -p '{\"imagePullSecrets\": [{\"name\": \"${registrySecret}\"}]}' --namespace ${namespace}"
}

def getChartFolder(String userSpecified, String currentChartFolder) {

  def newChartLocation = ""
  if (userSpecified) {
    print "User defined chart location specified: ${userSpecified}"
    return userSpecified
  } else {
    print "Finding actual chart folder below ${env.WORKSPACE}/${currentChartFolder}..."
    def fp = new hudson.FilePath(Jenkins.getInstance().getComputer(env['NODE_NAME']).getChannel(), env.WORKSPACE + "/" + currentChartFolder)
    def dirList = fp.listDirectories()
    if (dirList.size() > 1) {
      print "More than one directory in ${env.WORKSPACE}/${currentChartFolder}..."
      print "Directories found are:"
      def yamlList = []
      for (d in dirList) {
        print "${d}"
        def fileToTest = new hudson.FilePath(d, "Chart.yaml")
        if (fileToTest.exists()) {
          yamlList.add(d)
        }
      }
      if (yamlList.size() > 1) {
        print "-----------------------------------------------------------"
        print "*** More than one directory with Chart.yaml in ${env.WORKSPACE}/${currentChartFolder}."
        print "*** Please specify chart folder to use in your Jenkinsfile."
        print "*** Returning null."
        print "-----------------------------------------------------------"
        return null
      } else {
        if (yamlList.size() == 1) {
          newChartLocation = currentChartFolder + "/" + yamlList.get(0).getName()
          print "Chart.yaml found in ${newChartLocation}, setting as realChartFolder"
          return newChartLocation
        } else {
          print "-----------------------------------------------------------"
          print "*** No sub directory in ${env.WORKSPACE}/${currentChartFolder} contains a Chart.yaml, returning null"
          print "-----------------------------------------------------------"
          return null
        }
      }
    } else {
      if (dirList.size() == 1) {
        def chartFile = new hudson.FilePath(dirList.get(0), "Chart.yaml")
        newChartLocation = currentChartFolder + "/" + dirList.get(0).getName()
        if (chartFile.exists()) {
          print "Only one child directory found, setting realChartFolder to: ${newChartLocation}"
          return newChartLocation
        } else {
          print "-----------------------------------------------------------"
          print "*** Chart.yaml file does not exist in ${newChartLocation}, returning null"
          print "-----------------------------------------------------------"
          return null
        }
      } else {
        print "-----------------------------------------------------------"
        print "*** Chart directory ${env.WORKSPACE}/${currentChartFolder} has no subdirectories, returning null"
        print "-----------------------------------------------------------"
        return null
      }
    }
  }
}
