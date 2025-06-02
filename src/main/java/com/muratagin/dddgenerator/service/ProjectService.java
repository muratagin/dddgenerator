package com.muratagin.dddgenerator.service;

import com.muratagin.dddgenerator.dto.ProjectRequest;
import com.muratagin.dddgenerator.dto.CrossCuttingLibraryRequest;
import com.muratagin.dddgenerator.dto.EnvironmentalCredentialsRequest;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProjectService {

    private static final String DEFAULT_VERSION = "0.0.1-SNAPSHOT";
    private static final String DEFAULT_JAVA_VERSION = "21";
    private static final String DEFAULT_SPRING_BOOT_VERSION = "3.3.1";

    public byte[] generateProjectZip(ProjectRequest request, EnvironmentalCredentialsRequest envRequest) throws IOException {
        CrossCuttingLibraryRequest crossCuttingLib = request.getCrossCuttingLibrary();
        boolean useCrossCuttingLibrary = false;
        // Validation for CrossCuttingLibrary is now handled by @ValidCrossCuttingLibrary annotation on ProjectRequest
        // and @Valid on the crossCuttingLibrary field itself for its internal constraints (if any were added).
        // The custom validator CrossCuttingLibraryValidator handles the conditional logic.
        
        // Determine if a valid, fully populated cross-cutting library is being used.
        if (crossCuttingLib != null && 
            crossCuttingLib.isPopulated() && 
            crossCuttingLib.isFullyPopulated()) {
            // Further check if dependencies are correct (this part is also in the custom validator but good for safety here)
            List<String> requiredDeps = Arrays.asList("domain", "application", "persistence");
            Set<String> providedDeps = new HashSet<>(crossCuttingLib.getDependencies());
            if (providedDeps.containsAll(requiredDeps)) {
                useCrossCuttingLibrary = true;
            } else {
                // This case should ideally be caught by the validator, but as a safeguard:
                throw new IllegalArgumentException("Cross-cutting library dependencies must include 'domain', 'application', and 'persistence'. This should have been caught by earlier validation.");
            }
        } else if (crossCuttingLib != null && crossCuttingLib.isPopulated() && !crossCuttingLib.isFullyPopulated()){
            // This case should also be caught by the validator.
            throw new IllegalArgumentException("Cross-cutting library details are incomplete. This should have been caught by earlier validation.");
        }
        // If crossCuttingLib is null or not populated, useCrossCuttingLibrary remains false.

        String serverPort = (envRequest.getServerPort() != null && !envRequest.getServerPort().isEmpty()) ? envRequest.getServerPort() : "8080";
        String bannerMode = (envRequest.getBannerMode() != null && !envRequest.getBannerMode().isEmpty()) ? envRequest.getBannerMode() : "off";
        String springAppName = (envRequest.getApplicationName() != null && !envRequest.getApplicationName().isEmpty()) ? envRequest.getApplicationName() : request.getName();

        Path tempDir = Files.createTempDirectory("ddd-project-" + request.getArtifactId() + "-");
        String rootArtifactId = request.getArtifactId();
        String groupId = request.getGroupId();
        String version = (request.getVersion() != null && !request.getVersion().isEmpty()) ? request.getVersion() : DEFAULT_VERSION;
        String description = request.getDescription();

        String originalPackageName = request.getPackageName().toLowerCase();
        String sanitizedPackageName = originalPackageName.replace('-', '_');

        String basePackagePath = sanitizedPackageName.replace('.', File.separatorChar);
        String basePackageNameForClassGen = sanitizedPackageName;

        Path pomFile = Paths.get(tempDir.toString(), "pom.xml");
        Files.writeString(pomFile, generateRootPomXmlContent(request, version));

        String containerArtifactId = rootArtifactId + "-container";
        Path containerModuleDir = Paths.get(tempDir.toString(), containerArtifactId);
        Files.createDirectories(containerModuleDir);
        Path containerPom = Paths.get(containerModuleDir.toString(), "pom.xml");
        Files.writeString(containerPom, generateContainerPomXmlContent(request, containerArtifactId, rootArtifactId, version));
        Path containerMainJavaDir = Paths.get(containerModuleDir.toString(), "src", "main", "java", basePackagePath, "container");
        Files.createDirectories(containerMainJavaDir);
        Path containerAppFile = Paths.get(containerMainJavaDir.toString(), capitalize(rootArtifactId) + "ContainerApplication.java");
        Files.writeString(containerAppFile, generateContainerApplicationJavaContent(basePackageNameForClassGen, rootArtifactId, "container"));
        Path containerResources = Paths.get(containerModuleDir.toString(), "src", "main", "resources");
        Files.createDirectories(containerResources);
        Path applicationYml = Paths.get(containerResources.toString(), "application.yml");
        Files.writeString(applicationYml, generateApplicationYmlContent(springAppName, serverPort, bannerMode));

        // Always generate application-local.yml; the method provides defaults if details are not entered.
        Path applicationLocalYml = Paths.get(containerResources.toString(), "application-local.yml");
        Files.writeString(applicationLocalYml, generateApplicationLocalYmlContent(envRequest));

        // Conditionally generate profile-specific application.yml files
        if (envRequest.isGenerateDev()) {
            Path profileApplicationYml = Paths.get(containerResources.toString(), "application-dev.yml");
            Files.writeString(profileApplicationYml, generateProfileApplicationYmlContent(request.getName(), "dev"));
        }
        if (envRequest.isGenerateTest()) {
            Path profileApplicationYml = Paths.get(containerResources.toString(), "application-test.yml");
            Files.writeString(profileApplicationYml, generateProfileApplicationYmlContent(request.getName(), "test"));
        }
        if (envRequest.isGenerateUat()) {
            Path profileApplicationYml = Paths.get(containerResources.toString(), "application-uat.yml");
            Files.writeString(profileApplicationYml, generateProfileApplicationYmlContent(request.getName(), "uat"));
        }
        if (envRequest.isGenerateProd()) {
            Path profileApplicationYml = Paths.get(containerResources.toString(), "application-prod.yml");
            Files.writeString(profileApplicationYml, generateProfileApplicationYmlContent(request.getName(), "prod"));
        }

        String domainParentArtifactId = rootArtifactId + "-domain";
        Path domainModuleDir = Paths.get(tempDir.toString(), domainParentArtifactId);
        Files.createDirectories(domainModuleDir);
        Path domainParentPom = Paths.get(domainModuleDir.toString(), "pom.xml");
        Files.writeString(domainParentPom, generateDomainParentPomXmlContent(request, domainParentArtifactId, rootArtifactId, version));

        String domainCoreArtifactId = rootArtifactId + "-domain-core";
        Path domainCoreModuleDir = Paths.get(domainModuleDir.toString(), domainCoreArtifactId);
        Files.createDirectories(domainCoreModuleDir);
        Path domainCorePom = Paths.get(domainCoreModuleDir.toString(), "pom.xml");
        Files.writeString(domainCorePom, generateDomainCorePomXmlContent(request, domainCoreArtifactId, domainParentArtifactId, version));
        Path domainCoreMainJava = Paths.get(domainCoreModuleDir.toString(), "src", "main", "java", basePackagePath, "domain", "core");
        Files.createDirectories(domainCoreMainJava);

        if (!useCrossCuttingLibrary) {
            Path domainCoreEntityDir = Paths.get(domainCoreMainJava.toString(), "entity");
            Files.createDirectories(domainCoreEntityDir);
            Files.writeString(Paths.get(domainCoreEntityDir.toString(), "AggregateRoot.java"), generateDefaultAggregateRootContent(basePackageNameForClassGen));
            Files.writeString(Paths.get(domainCoreEntityDir.toString(), "BaseDomainEntity.java"), generateDefaultBaseDomainEntityContent(basePackageNameForClassGen));
        } else {
            Files.createFile(Paths.get(domainCoreMainJava.toString(), ".gitkeep"));
        }

        String appServiceArtifactId = rootArtifactId + "-application-service";
        Path appServiceModuleDir = Paths.get(domainModuleDir.toString(), appServiceArtifactId);
        Files.createDirectories(appServiceModuleDir);
        Path appServicePom = Paths.get(appServiceModuleDir.toString(), "pom.xml");
        Files.writeString(appServicePom, generateApplicationServicePomXmlContent(request, appServiceArtifactId, domainParentArtifactId, domainCoreArtifactId, version));
        Path appServiceMainJava = Paths.get(appServiceModuleDir.toString(), "src", "main", "java", basePackagePath, "domain", "applicationservice");
        Files.createDirectories(appServiceMainJava);
        Files.createFile(Paths.get(appServiceMainJava.toString(), ".gitkeep"));

        String infraParentArtifactId = rootArtifactId + "-infrastructure";
        Path infraModuleDir = Paths.get(tempDir.toString(), infraParentArtifactId);
        Files.createDirectories(infraModuleDir);
        Path infraParentPom = Paths.get(infraModuleDir.toString(), "pom.xml");
        Files.writeString(infraParentPom, generateInfrastructureParentPomXmlContent(request, infraParentArtifactId, rootArtifactId, version));

        String persistenceArtifactId = rootArtifactId + "-persistence";
        Path persistenceModuleDir = Paths.get(infraModuleDir.toString(), persistenceArtifactId);
        Files.createDirectories(persistenceModuleDir);
        Path persistencePom = Paths.get(persistenceModuleDir.toString(), "pom.xml");
        Files.writeString(persistencePom, generatePersistencePomXmlContent(request, persistenceArtifactId, infraParentArtifactId, appServiceArtifactId, version));
        Path persistenceMainJava = Paths.get(persistenceModuleDir.toString(), "src", "main", "java", basePackagePath, "infrastructure", "persistence");
        Files.createDirectories(persistenceMainJava);

        if (!useCrossCuttingLibrary) {
            Path persistenceEntityDir = Paths.get(persistenceMainJava.toString(), "entity");
            Files.createDirectories(persistenceEntityDir);
            Files.writeString(Paths.get(persistenceEntityDir.toString(), "BaseEntity.java"), generateDefaultBaseEntityContent(basePackageNameForClassGen));
        } else {
            Files.createFile(Paths.get(persistenceMainJava.toString(), ".gitkeep"));
        }

        String appLayerArtifactId = rootArtifactId + "-application";
        Path appLayerModuleDir = Paths.get(tempDir.toString(), appLayerArtifactId);
        Files.createDirectories(appLayerModuleDir);
        Path appLayerPom = Paths.get(appLayerModuleDir.toString(), "pom.xml");
        Files.writeString(appLayerPom, generateApplicationLayerPomXmlContent(request, appLayerArtifactId, rootArtifactId, appServiceArtifactId, version));
        Path appLayerMainJava = Paths.get(appLayerModuleDir.toString(), "src", "main", "java", basePackagePath, "application");
        Files.createDirectories(appLayerMainJava);

        if (!useCrossCuttingLibrary) {
            Path appLayerExceptionDir = Paths.get(appLayerMainJava.toString(), "exception");
            Files.createDirectories(appLayerExceptionDir);
            Files.writeString(Paths.get(appLayerExceptionDir.toString(), "GlobalExceptionHandler.java"), generateDefaultGlobalExceptionHandlerContent(basePackageNameForClassGen));

            Path appLayerPayloadDir = Paths.get(appLayerMainJava.toString(), "payload");
            Files.createDirectories(appLayerPayloadDir);
            Files.writeString(Paths.get(appLayerPayloadDir.toString(), "ResultObject.java"), generateDefaultResultObjectContent(basePackageNameForClassGen));
        } else {
            Files.createFile(Paths.get(appLayerMainJava.toString(), ".gitkeep"));
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zipOut = new ZipOutputStream(baos)) {
            zipDirectory(tempDir.toFile(), "", zipOut);
        }

        deleteDirectoryRecursively(tempDir);

        return baos.toByteArray();
    }

    private void zipDirectory(File folderToZip, String parentName, ZipOutputStream zipOut) throws IOException {
        File[] children = folderToZip.listFiles();
        if (children == null) {
            return;
        }

        for (File file : children) {
            String zipEntryName = parentName.isEmpty() ? file.getName() : parentName + "/" + file.getName();
            if (file.isDirectory()) {
                zipDirectory(file, zipEntryName, zipOut);
            } else {
                try (FileInputStream fis = new FileInputStream(file)) {
                    ZipEntry zipEntry = new ZipEntry(zipEntryName);
                    zipOut.putNextEntry(zipEntry);

                    byte[] bytes = new byte[1024];
                    int length;
                    while ((length = fis.read(bytes)) >= 0) {
                        zipOut.write(bytes, 0, length);
                    }
                    zipOut.closeEntry();
                }
            }
        }
    }
    
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return Arrays.stream(str.split("-"))
                     .filter(part -> part != null && !part.isEmpty())
                     .map(part -> part.substring(0, 1).toUpperCase() + part.substring(1).toLowerCase())
                     .collect(Collectors.joining());
    }

    private String generateRootPomXmlContent(ProjectRequest request, String effectiveVersion) {
        String artifactId = request.getArtifactId();
        String javaVersion = (request.getJavaVersion() != null && !request.getJavaVersion().isEmpty()) ? request.getJavaVersion() : DEFAULT_JAVA_VERSION;
        String springBootVersion = (request.getSpringBootVersion() != null && !request.getSpringBootVersion().isEmpty()) ? request.getSpringBootVersion() : DEFAULT_SPRING_BOOT_VERSION;
        String lombokVersion = request.getLombokVersion();

        StringBuilder propertiesBuilder = new StringBuilder();
        propertiesBuilder.append(String.format("        <java.version>%s</java.version>\n", javaVersion));
        propertiesBuilder.append("        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n");

        String lombokDependencyManagementEntry = "";
        String lombokCompilerPathVersionTag = "";

        if (lombokVersion != null && !lombokVersion.isEmpty()) {
            propertiesBuilder.append(String.format("        <lombok.version>%s</lombok.version>\n", lombokVersion));
            String lombokVersionPropertyRef = "${lombok.version}";
            lombokDependencyManagementEntry =
                    "            <dependency>\n" +
                    "                <groupId>org.projectlombok</groupId>\n" +
                    "                <artifactId>lombok</artifactId>\n" +
                    String.format("                <version>%s</version>\n", lombokVersionPropertyRef) +
                    "                <scope>provided</scope>\n" +
                    "            </dependency>";
            lombokCompilerPathVersionTag = String.format("                            <version>%s</version>\n", lombokVersionPropertyRef);
        } else {
        }

        StringBuilder crossCuttingDepsXmlBuilder = new StringBuilder();
        CrossCuttingLibraryRequest crossCuttingLib = request.getCrossCuttingLibrary();

        if (crossCuttingLib != null && crossCuttingLib.getName() != null && !crossCuttingLib.getName().isEmpty() &&
            crossCuttingLib.getVersion() != null && !crossCuttingLib.getVersion().isEmpty() &&
            crossCuttingLib.getGroupId() != null && !crossCuttingLib.getGroupId().isEmpty() &&
            crossCuttingLib.getDependencies() != null && !crossCuttingLib.getDependencies().isEmpty()) {
            
            String libName = crossCuttingLib.getName();
            String libVersionProperty = libName.toLowerCase().replace("-", ".") + ".version";
            propertiesBuilder.append(String.format("        <%s>%s</%s>\n", libVersionProperty, crossCuttingLib.getVersion(), libVersionProperty));

            crossCuttingDepsXmlBuilder.append("\n            <!-- Cross-Cutting Library: ").append(libName).append(" -->");

            List<String> actualDeps = crossCuttingLib.getDependencies();
            
            crossCuttingDepsXmlBuilder.append("\n");

            for (int i = 0; i < actualDeps.size(); i++) {
                String depSuffix = actualDeps.get(i);
                String depBlock =
                        "            <dependency>\n" +
                        String.format("                <groupId>%s</groupId>\n", crossCuttingLib.getGroupId()) +
                        String.format("                <artifactId>%s-%s</artifactId>\n", libName, depSuffix) +
                        String.format("                <version>${%s}</version>\n", libVersionProperty) +
                        "            </dependency>";
                crossCuttingDepsXmlBuilder.append(depBlock);
                if (i < actualDeps.size() - 1) {
                    crossCuttingDepsXmlBuilder.append("\n");
                }
            }
        }

        return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>%s</version>
        <relativePath/>
    </parent>
    <groupId>%s</groupId>
    <artifactId>%s</artifactId>
    <version>%s</version>
    <name>%s</name>
    <description>%s</description>

    <packaging>pom</packaging>

    <modules>
        <module>%s-container</module>
        <module>%s-domain</module>
        <module>%s-infrastructure</module>
        <module>%s-application</module>
    </modules>

    <properties>
%s    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>%s</groupId>
                <artifactId>%s-container</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>%s</groupId>
                <artifactId>%s-application</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>%s</groupId>
                <artifactId>%s-domain-core</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>%s</groupId>
                <artifactId>%s-application-service</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>%s</groupId>
                <artifactId>%s-persistence</artifactId>
                <version>${project.version}</version>
            </dependency>

            <!-- Common Third-Party Dependencies -->
%s
%s
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                </plugin>
            </plugins>
        </pluginManagement>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
%s                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
                """,
                springBootVersion,
                request.getGroupId(),
                artifactId,
                effectiveVersion,
                request.getName(),
                request.getDescription(),
                artifactId, artifactId, artifactId, artifactId,
                propertiesBuilder.toString(),
                request.getGroupId(), artifactId,
                request.getGroupId(), artifactId,
                request.getGroupId(), artifactId,
                request.getGroupId(), artifactId,
                request.getGroupId(), artifactId,
                lombokDependencyManagementEntry,
                crossCuttingDepsXmlBuilder.toString(),
                lombokCompilerPathVersionTag
        );
    }

    private String generateContainerPomXmlContent(ProjectRequest request, String containerArtifactId, String rootArtifactId, String effectiveVersion) {
        String sanitizedPackageName = request.getPackageName().replace('-', '_');
        return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId>
        <version>%s</version>
    </parent>

    <artifactId>%s</artifactId>

    <properties>
        <start-class>%s.container.%sContainerApplication</start-class>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>

        <!-- Project Modules -->
        <dependency>
            <groupId>%s</groupId>
            <artifactId>%s-domain-core</artifactId>
        </dependency>
        <dependency>
            <groupId>%s</groupId>
            <artifactId>%s-application-service</artifactId>
        </dependency>
        <dependency>
            <groupId>%s</groupId>
            <artifactId>%s-application</artifactId>
        </dependency>
        <dependency>
            <groupId>%s</groupId>
            <artifactId>%s-persistence</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <mainClass>${start-class}</mainClass>
                </configuration>
                
                <executions>
                    <execution>
                        <goals>
                            <goal>repackage</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
                """,
                request.getGroupId(),
                rootArtifactId,
                effectiveVersion,
                containerArtifactId,
                sanitizedPackageName,
                capitalize(rootArtifactId),
                request.getGroupId(), rootArtifactId,
                request.getGroupId(), rootArtifactId,
                request.getGroupId(), rootArtifactId,
                request.getGroupId(), rootArtifactId
        );
    }

    private String generateContainerApplicationJavaContent(String basePackageName, String rootArtifactId, String moduleSuffix) {
        String appName = capitalize(rootArtifactId) + capitalize(moduleSuffix) + "Application";
        String moduleSpecificPackage = basePackageName + "." + moduleSuffix.replace("-", "");

        return String.format(
                "package %s;\\n\\n" +
                "import org.springframework.boot.SpringApplication;\\n" +
                "import org.springframework.boot.autoconfigure.SpringBootApplication;\\n" +
                "import org.springframework.context.annotation.ComponentScan;\\n\\n" +
                "@SpringBootApplication\\n" +
                "@ComponentScan(basePackages = {\\\"%s\\\"})\\n" +
                "public class %s {\\n\\n" +
                "    public static void main(String[] args) {\\n" +
                "        SpringApplication.run(%s.class, args);\\n" +
                "    }\\n\\n" +
                "}\\n",
                moduleSpecificPackage,
                basePackageName,
                appName,
                appName
        );
    }

    private String generateApplicationYmlContent(String springApplicationName, String serverPort, String bannerMode) {
        // bannerMode argument is no longer used as it's hardcoded to off.
        // springApplicationName and serverPort are used.
        return String.format("""
spring:
  application:
    name: ${SPRING_APPLICATION_NAME:%s}
  main:
    banner-mode: off
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:local}
  # Datasource configuration is expected to be provided by an active profile (e.g., local, dev, prod)
  # or environment variables if no specific datasource is configured in a profile.
  jpa:
    hibernate:
      ddl-auto: ${JPA_HIBERNATE_DDL_AUTO:validate} # Defaults to validate; can be overridden by profiles
server:
  port: ${SERVER_PORT:%s}
""", springApplicationName, serverPort);
    }

    private String generateApplicationLocalYmlContent(EnvironmentalCredentialsRequest envRequest) {
        String url = (envRequest.getLocalDatasourceUrl() != null && !envRequest.getLocalDatasourceUrl().isEmpty())
                     ? envRequest.getLocalDatasourceUrl() : "jdbc:postgresql://localhost:5432/your_db_name_local";
        String username = (envRequest.getLocalDatasourceUsername() != null && !envRequest.getLocalDatasourceUsername().isEmpty())
                          ? envRequest.getLocalDatasourceUsername() : "your_username_local";
        String password = envRequest.getLocalDatasourcePassword() != null 
                          ? envRequest.getLocalDatasourcePassword() : "your_password_local";

        return String.format("""
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL_LOCAL:%s}
    username: ${SPRING_DATASOURCE_USERNAME_LOCAL:%s}
    password: ${SPRING_DATASOURCE_PASSWORD_LOCAL:%s}
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
""", url, username, password);
    }

    private String generateProfileApplicationYmlContent(String baseSpringApplicationName, String profile) {
        // baseSpringApplicationName is not used here.
        return String.format("""
# Configuration for '%s' environment.
# Expecting datasource credentials to be provided via environment variables or a secure configuration server.
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
""", profile);
    }

    private String generateDomainParentPomXmlContent(ProjectRequest request, String domainParentArtifactId, String rootArtifactId, String effectiveVersion) {
        return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId>
        <version>%s</version>
    </parent>

    <artifactId>%s</artifactId>

    <packaging>pom</packaging>

    <modules>
        <module>%s-application-service</module>    
        <module>%s-domain-core</module>
    </modules>
</project>
                """,
                request.getGroupId(),
                rootArtifactId,
                effectiveVersion,
                domainParentArtifactId,
                rootArtifactId,
                rootArtifactId
        );
    }

    private String generateDomainCorePomXmlContent(ProjectRequest request, String domainCoreArtifactId, String domainParentArtifactId, String effectiveVersion) {
         return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId>
        <version>%s</version>
    </parent>

    <artifactId>%s</artifactId>

</project>
                """,
                request.getGroupId(),
                domainParentArtifactId,
                effectiveVersion,
                domainCoreArtifactId
        );
    }
    
    private String generateApplicationServicePomXmlContent(ProjectRequest request, String appServiceArtifactId, String domainParentArtifactId, String domainCoreArtifactId, String effectiveVersion) {
        return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId>
        <version>%s</version>
    </parent>

    <artifactId>%s</artifactId>

    <dependencies>
        <!-- Domain Core -->
        <dependency>
            <groupId>%s</groupId>
            <artifactId>%s</artifactId>
        </dependency>

        <!-- Spring Boot Validation -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Spring Transactions -->
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-tx</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>

        <!-- Jackson Annotations (often used with DTOs) -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
        </dependency>

        <!-- Test Dependencies -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
                """,
                request.getGroupId(),
                domainParentArtifactId,
                effectiveVersion,
                appServiceArtifactId,
                request.getGroupId(), domainCoreArtifactId
        );
    }

    private String generateInfrastructureParentPomXmlContent(ProjectRequest request, String infraParentArtifactId, String rootArtifactId, String effectiveVersion) {
        return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId>
        <version>%s</version>
    </parent>

    <artifactId>%s</artifactId>

    <packaging>pom</packaging>

    <modules>
        <module>%s-persistence</module>
    </modules>
</project>
                """,
                request.getGroupId(),
                rootArtifactId,
                effectiveVersion,
                infraParentArtifactId,
                rootArtifactId
        );
    }

    private String generatePersistencePomXmlContent(ProjectRequest request, String persistenceArtifactId, String infraParentArtifactId, String appServiceArtifactId, String effectiveVersion) {
        return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId>
        <version>%s</version>
    </parent>

    <artifactId>%s</artifactId>

    <dependencies>
        <dependency>
            <groupId>%s</groupId>
            <artifactId>%s</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
    </dependencies>
</project>
                """,
                request.getGroupId(),
                infraParentArtifactId,
                effectiveVersion,
                persistenceArtifactId,
                request.getGroupId(), appServiceArtifactId
        );
    }

    private String generateApplicationLayerPomXmlContent(ProjectRequest request, String appLayerArtifactId, String rootArtifactId, String appServiceArtifactId, String effectiveVersion) {
        return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId>
        <version>%s</version>
    </parent>

    <artifactId>%s</artifactId>

    <dependencies>
        <dependency>
            <groupId>%s</groupId>
            <artifactId>%s</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
    </dependencies>
</project>
                """,
                request.getGroupId(),
                rootArtifactId,
                effectiveVersion,
                appLayerArtifactId,
                request.getGroupId(), appServiceArtifactId
        );
    }

    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.notExists(path)) {
            return;
        }
        Files.walk(path)
                .sorted((o1, o2) -> o2.compareTo(o1))
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        System.err.println("Failed to delete: " + p + " - " + e.getMessage());
                    }
                });
    }

    private String generateDefaultGlobalExceptionHandlerContent(String basePackageName) {
        return String.format("""
package %s.application.exception;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ResponseBody
    @ExceptionHandler(value = {ValidationException.class, ConstraintViolationException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handle(ValidationException exception) {
        log.error(exception.getMessage(), exception);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problemDetail.setType(URI.create("https://www.rfc-editor.org/rfc/rfc9110#status.400"));
        return problemDetail;
    }

    @ResponseBody
    @ExceptionHandler(value = {NoResourceFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ProblemDetail handleException(NoResourceFoundException exception) {
        log.error(exception.getMessage(), exception);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problemDetail.setType(URI.create("https://www.rfc-editor.org/rfc/rfc9110#status.404"));
        return problemDetail;
    }
}
""", basePackageName);
    }

    private String generateDefaultResultObjectContent(String basePackageName) {
        return String.format("""
package %s.application.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
public class ResultObject<T> {

    private Boolean isSuccess;
    private T data;
    private List<String> messages;

    public static <T> ResultObject<T> empty() {
        return success(null);
    }

    public static <T> ResultObject<T> success(T data) {
        return ResultObject.<T>builder()
                .isSuccess(true)
                .data(data)
                .build();
    }

    public static <T> ResultObject<T> success(T data, String message) {
        return ResultObject.<T>builder()
                .isSuccess(true)
                .data(data)
                .messages(List.of(message))
                .build();
    }

    public static <T> ResultObject<T> error(List<String> failureMessages) {
        return ResultObject.<T>builder()
                .isSuccess(false)
                .messages(failureMessages)
                .build();
    }

    public static <T> ResultObject<T> error(String failureMessage) {
        return ResultObject.<T>builder()
                .isSuccess(false)
                .messages(List.of(failureMessage))
                .build();
    }
}
""", basePackageName);
    }

    private String generateDefaultAggregateRootContent(String basePackageName) {
        return String.format("""
package %s.domain.core.entity;

public abstract class AggregateRoot<ID> extends BaseDomainEntity<ID> {
}
""", basePackageName);
    }

    private String generateDefaultBaseDomainEntityContent(String basePackageName) {
        return String.format("""
package %s.domain.core.entity;

import java.util.Objects;

public abstract class BaseDomainEntity<ID> {

    private ID id;

    public ID getId() {
        return id;
    }

    public void setId(ID id) {
        this.id = id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BaseDomainEntity<?> that = (BaseDomainEntity<?>) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
""", basePackageName);
    }

    private String generateDefaultBaseEntityContent(String basePackageName) {
        return String.format("""
package %s.infrastructure.persistence.entity;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@MappedSuperclass
public class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    protected UUID id;

    protected Boolean isDeleted;

    public BaseEntity(UUID id, Boolean isDeleted) {
        this.id = id;
        this.isDeleted = isDeleted;
    }
}
""", basePackageName);
    }
}
