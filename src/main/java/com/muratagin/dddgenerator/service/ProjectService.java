package com.muratagin.dddgenerator.service;

import com.muratagin.dddgenerator.domain.request.EnvironmentalCredentialsRequest;
import com.muratagin.dddgenerator.dto.ProjectRequest;
import com.muratagin.dddgenerator.dto.CrossCuttingLibraryRequest;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.stream.Collectors;
import java.util.Locale;

@Service
public class ProjectService {

    private static final String DEFAULT_VERSION = "0.0.1-SNAPSHOT";
    private static final String DEFAULT_JAVA_VERSION = "21";
    private static final String DEFAULT_SPRING_BOOT_VERSION = "3.3.1";

    public byte[] generateProjectZip(ProjectRequest projectRequest, EnvironmentalCredentialsRequest environmentalCredentialsRequest) throws IOException {
        String tempDirName = "project-" + projectRequest.getArtifactId() + "-" + System.currentTimeMillis();
        Path tempDirPath = Files.createTempDirectory(tempDirName);
        CrossCuttingLibraryRequest crossCuttingLib = projectRequest.getCrossCuttingLibrary();
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

        String serverPort = (environmentalCredentialsRequest.getServerPort() != null && !environmentalCredentialsRequest.getServerPort().isEmpty()) ? environmentalCredentialsRequest.getServerPort() : "8080";
        String bannerMode = (environmentalCredentialsRequest.getBannerMode() != null && !environmentalCredentialsRequest.getBannerMode().isEmpty()) ? environmentalCredentialsRequest.getBannerMode() : "off";
        String springAppName = (environmentalCredentialsRequest.getApplicationName() != null && !environmentalCredentialsRequest.getApplicationName().isEmpty()) ? environmentalCredentialsRequest.getApplicationName() : projectRequest.getName();

        Path tempDir = Files.createTempDirectory("ddd-project-" + projectRequest.getArtifactId() + "-");
        String rootArtifactId = projectRequest.getArtifactId();
        String groupId = projectRequest.getGroupId();
        String version = (projectRequest.getVersion() != null && !projectRequest.getVersion().isEmpty()) ? projectRequest.getVersion() : DEFAULT_VERSION;
        String description = projectRequest.getDescription();

        String originalPackageName = projectRequest.getPackageName().toLowerCase();
        String sanitizedPackageName = originalPackageName.replace('-', '_');

        String basePackagePath = sanitizedPackageName.replace('.', File.separatorChar);
        String basePackageNameForClassGen = sanitizedPackageName;

        Path pomFile = Paths.get(tempDir.toString(), "pom.xml");
        Files.writeString(pomFile, generateRootPomXmlContent(projectRequest, version));

        String containerArtifactId = rootArtifactId + "-container";
        Path containerModuleDir = Paths.get(tempDir.toString(), containerArtifactId);
        Files.createDirectories(containerModuleDir);
        Path containerPom = Paths.get(containerModuleDir.toString(), "pom.xml");
        Files.writeString(containerPom, generateContainerPomXmlContent(projectRequest, containerArtifactId, rootArtifactId, version));
        Path containerMainJavaDir = Paths.get(containerModuleDir.toString(), "src", "main", "java", basePackagePath, "container");
        Files.createDirectories(containerMainJavaDir);
        Path containerAppFile = Paths.get(containerMainJavaDir.toString(), capitalize(rootArtifactId) + "ContainerApplication.java");
        Files.writeString(containerAppFile, generateContainerApplicationJavaContent(basePackageNameForClassGen, projectRequest, rootArtifactId, "container", useCrossCuttingLibrary));
        Path containerResources = Paths.get(containerModuleDir.toString(), "src", "main", "resources");
        Files.createDirectories(containerResources);
        Path applicationYml = Paths.get(containerResources.toString(), "application.yml");
        Files.writeString(applicationYml, generateApplicationYmlContent(springAppName, serverPort, bannerMode));

        // Always generate application-local.yml; the method provides defaults if details are not entered.
        Path applicationLocalYml = Paths.get(containerResources.toString(), "application-local.yml");
        Files.writeString(applicationLocalYml, generateApplicationLocalYmlContent(environmentalCredentialsRequest));

        // Conditionally generate profile-specific application.yml files
        if (environmentalCredentialsRequest.isGenerateDev()) {
            Path profileApplicationYml = Paths.get(containerResources.toString(), "application-dev.yml");
            Files.writeString(profileApplicationYml, generateProfileApplicationYmlContent(projectRequest.getName(), "dev"));
        }
        if (environmentalCredentialsRequest.isGenerateTest()) {
            Path profileApplicationYml = Paths.get(containerResources.toString(), "application-test.yml");
            Files.writeString(profileApplicationYml, generateProfileApplicationYmlContent(projectRequest.getName(), "test"));
        }
        if (environmentalCredentialsRequest.isGenerateUat()) {
            Path profileApplicationYml = Paths.get(containerResources.toString(), "application-uat.yml");
            Files.writeString(profileApplicationYml, generateProfileApplicationYmlContent(projectRequest.getName(), "uat"));
        }
        if (environmentalCredentialsRequest.isGenerateProd()) {
            Path profileApplicationYml = Paths.get(containerResources.toString(), "application-prod.yml");
            Files.writeString(profileApplicationYml, generateProfileApplicationYmlContent(projectRequest.getName(), "prod"));
        }

        String domainParentArtifactId = rootArtifactId + "-domain";
        Path domainModuleDir = Paths.get(tempDir.toString(), domainParentArtifactId);
        Files.createDirectories(domainModuleDir);
        Path domainParentPom = Paths.get(domainModuleDir.toString(), "pom.xml");
        Files.writeString(domainParentPom, generateDomainParentPomXmlContent(projectRequest, domainParentArtifactId, rootArtifactId, version));

        String domainCoreArtifactId = rootArtifactId + "-domain-core";
        Path domainCoreModuleDir = Paths.get(domainModuleDir.toString(), domainCoreArtifactId);
        Files.createDirectories(domainCoreModuleDir);
        Path domainCorePom = Paths.get(domainCoreModuleDir.toString(), "pom.xml");
        Files.writeString(domainCorePom, generateDomainCorePomXmlContent(projectRequest, domainCoreArtifactId, domainParentArtifactId, version));
        Path domainCoreMainJava = Paths.get(domainCoreModuleDir.toString(), "src", "main", "java", basePackagePath, "domain", "core");
        Files.createDirectories(domainCoreMainJava);

        String domainExceptionClassName = useCrossCuttingLibrary ? capitalize(rootArtifactId) + "DomainException" : "DomainException";

        Path domainCoreExceptionDir = Paths.get(domainCoreMainJava.toString(), "exception");
        Files.createDirectories(domainCoreExceptionDir);
        Files.writeString(Paths.get(domainCoreExceptionDir.toString(), domainExceptionClassName + ".java"), generateDomainExceptionContent(basePackageNameForClassGen, domainExceptionClassName));
        Files.writeString(Paths.get(domainCoreExceptionDir.toString(), "DomainEntityNotFoundException.java"), generateDomainEntityNotFoundExceptionContent(basePackageNameForClassGen));
        Files.writeString(Paths.get(domainCoreExceptionDir.toString(), "RepositoryOutputPortException.java"), generateRepositoryOutputPortExceptionContent(basePackageNameForClassGen));

        if (environmentalCredentialsRequest.getSelectedSchema() != null && !environmentalCredentialsRequest.getSelectedSchema().isEmpty()) {
            generateDomainClasses(environmentalCredentialsRequest, domainCoreMainJava, basePackageNameForClassGen);
        }

        if (!useCrossCuttingLibrary) {
            Path domainCoreEntityDir = Paths.get(domainCoreMainJava.toString(), "entity");
            Files.createDirectories(domainCoreEntityDir);
            Files.writeString(Paths.get(domainCoreEntityDir.toString(), "AggregateRoot.java"), generateDefaultAggregateRootContent(basePackageNameForClassGen));
            Files.writeString(Paths.get(domainCoreEntityDir.toString(), "BaseDomainEntity.java"), generateDefaultBaseDomainEntityContent(basePackageNameForClassGen));

            Path domainCoreValueObjectDir = Paths.get(domainCoreMainJava.toString(), "valueobject");
            Files.createDirectories(domainCoreValueObjectDir);
            Files.writeString(Paths.get(domainCoreValueObjectDir.toString(), "BaseId.java"), generateDefaultBaseIdContent(basePackageNameForClassGen));
        }

        String appServiceArtifactId = rootArtifactId + "-application-service";
        Path appServiceModuleDir = Paths.get(domainModuleDir.toString(), appServiceArtifactId);
        Files.createDirectories(appServiceModuleDir);
        Path appServicePom = Paths.get(appServiceModuleDir.toString(), "pom.xml");
        Files.writeString(appServicePom, generateApplicationServicePomXmlContent(projectRequest, appServiceArtifactId, domainParentArtifactId, domainCoreArtifactId, version));
        Path appServiceMainJava = Paths.get(appServiceModuleDir.toString(), "src", "main", "java", basePackagePath, "domain", "applicationservice");
        Files.createDirectories(appServiceMainJava);
        Files.createFile(Paths.get(appServiceMainJava.toString(), ".gitkeep"));

        String infraParentArtifactId = rootArtifactId + "-infrastructure";
        Path infraModuleDir = Paths.get(tempDir.toString(), infraParentArtifactId);
        Files.createDirectories(infraModuleDir);
        Path infraParentPom = Paths.get(infraModuleDir.toString(), "pom.xml");
        Files.writeString(infraParentPom, generateInfrastructureParentPomXmlContent(projectRequest, infraParentArtifactId, rootArtifactId, version));

        String persistenceArtifactId = rootArtifactId + "-persistence";
        Path persistenceModuleDir = Paths.get(infraModuleDir.toString(), persistenceArtifactId);
        Files.createDirectories(persistenceModuleDir);
        Path persistencePom = Paths.get(persistenceModuleDir.toString(), "pom.xml");
        Files.writeString(persistencePom, generatePersistencePomXmlContent(projectRequest, persistenceArtifactId, infraParentArtifactId, appServiceArtifactId, version));
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
        Files.writeString(appLayerPom, generateApplicationLayerPomXmlContent(projectRequest, appLayerArtifactId, rootArtifactId, appServiceArtifactId, version));
        Path appLayerMainJava = Paths.get(appLayerModuleDir.toString(), "src", "main", "java", basePackagePath, "application");
        Files.createDirectories(appLayerMainJava);

        if (!useCrossCuttingLibrary) {
            String globalExceptionHandlerClassName = "GlobalExceptionHandler";
            Path appLayerExceptionDir = Paths.get(appLayerMainJava.toString(), "exception");
            Files.createDirectories(appLayerExceptionDir);
            Files.writeString(Paths.get(appLayerExceptionDir.toString(), globalExceptionHandlerClassName + ".java"), generateDefaultGlobalExceptionHandlerContent(basePackageNameForClassGen, globalExceptionHandlerClassName, domainExceptionClassName));

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
                     .map(part -> part.substring(0, 1).toUpperCase(Locale.ENGLISH) + part.substring(1).toLowerCase(Locale.ENGLISH))
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
            String libVersionProperty = libName.toLowerCase(Locale.ENGLISH).replace("-", "") + ".version";
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

    private String generateContainerApplicationJavaContent(String basePackageName, ProjectRequest projectRequest, String rootArtifactId, String moduleSuffix, boolean useCrossCuttingLibrary) {
        String appName = capitalize(rootArtifactId) + capitalize(moduleSuffix) + "Application";
        String moduleSpecificPackage = basePackageName + "." + moduleSuffix.replace("-", "");

        if (useCrossCuttingLibrary) {
            CrossCuttingLibraryRequest crossCuttingLib = projectRequest.getCrossCuttingLibrary();
            String crossCuttingBasePackage = crossCuttingLib.getGroupId() + "." + crossCuttingLib.getName().replace("-", "");

            return String.format("""
package %s;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@EnableJpaRepositories(basePackages = {"%s.infrastructure.persistence"})
@EntityScan(basePackages = {"%s.infrastructure.persistence"})
@SpringBootApplication(scanBasePackages = {"%s", "%s"})
public class %s {

    public static void main(String[] args) {
        SpringApplication.run(%s.class, args);
    }

}
""", moduleSpecificPackage, basePackageName, basePackageName, basePackageName, crossCuttingBasePackage, appName, appName).stripIndent();
        } else {
            return String.format("""
package %s;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@EnableJpaRepositories(basePackages = {"%s.infrastructure.persistence"})
@EntityScan(basePackages = {"%s.infrastructure.persistence"})
@SpringBootApplication(scanBasePackages = {"%s"})
public class %s {

    public static void main(String[] args) {
        SpringApplication.run(%s.class, args);
    }

}
""", moduleSpecificPackage, basePackageName, basePackageName, basePackageName, appName, appName).stripIndent();
        }
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

    private String generateDefaultGlobalExceptionHandlerContent(String basePackageName, String globalExceptionHandlerClassName, String domainExceptionClassName) {
        return String.format("""
package %s.application.exception;

import %s.domain.core.exception.%s;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.net.URI;

@Slf4j
@ControllerAdvice
public class %s {

    @ResponseBody
    @ExceptionHandler(value = {%s.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleException(%s exception) {
        log.error(exception.getMessage(), exception);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problemDetail.setType(URI.create("https://www.rfc-editor.org/rfc/rfc9110#status.400"));
        return problemDetail;
    }

    @ResponseBody
    @ExceptionHandler(value = {DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ProblemDetail handleException(DataIntegrityViolationException exception) {
        log.error(exception.getMessage(), exception);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problemDetail.setType(URI.create("https://www.rfc-editor.org/rfc/rfc9110#status.404"));
        return problemDetail;
    }
}
""", basePackageName, basePackageName, domainExceptionClassName, globalExceptionHandlerClassName, domainExceptionClassName, domainExceptionClassName, domainExceptionClassName, domainExceptionClassName);
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

    private String generateDefaultBaseIdContent(String basePackageName) {
        return String.format("""
package %s.domain.core.valueobject;

import java.util.Objects;

public abstract class BaseId<T> {

    private final T value;

    protected BaseId(T value) {
        this.value = value;
    }

    public T getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BaseId<?> baseId = (BaseId<?>) o;
        return Objects.equals(value, baseId.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
""", basePackageName);
    }

    private String generateDomainExceptionContent(String basePackageName, String className) {
        return String.format("""
package %s.domain.core.exception;

public class %s extends RuntimeException {

    public %s(String message) {
        super(message);
    }

    public %s(String message, Throwable cause) {
        super(message, cause);
    }
}
""", basePackageName, className, className, className);
    }

    private String generateDomainEntityNotFoundExceptionContent(String basePackageName) {
        return String.format("""
package %s.domain.core.exception;

public class DomainEntityNotFoundException extends RuntimeException {

    public DomainEntityNotFoundException() {
        super();
    }
}
""", basePackageName);
    }

    private String generateRepositoryOutputPortExceptionContent(String basePackageName) {
        return String.format("""
package %s.domain.core.exception;

public class RepositoryOutputPortException extends RuntimeException {

    public RepositoryOutputPortException() {
        super();
    }
}
""", basePackageName);
    }

    private void generateDomainClasses(EnvironmentalCredentialsRequest envRequest, Path domainCoreMainJava, String basePackageName) {
        String url = envRequest.getLocalDatasourceUrl();
        String username = envRequest.getLocalDatasourceUsername();
        String password = envRequest.getLocalDatasourcePassword();
        String schema = envRequest.getSelectedSchema();

        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            List<String> tables = getTables(conn, schema);
            Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys = getDetailedForeignKeys(conn, schema);

            Map<String, Set<String>> foreignKeys = new HashMap<>();
            for (Map.Entry<String, Map<String, ForeignKeyInfo>> entry : detailedForeignKeys.entrySet()) {
                foreignKeys.put(entry.getKey(), entry.getValue().values().stream().map(ForeignKeyInfo::getPkTableName).collect(Collectors.toSet()));
            }

            Set<String> aggregateRoots = determineAggregateRoots(tables, foreignKeys);
            Map<String, String> tableEntityTypes = envRequest.getTableEntityTypes();
            Map<String, String> columnToEnumMap = new HashMap<>();

            for (String table : tables) {
                List<Map<String, String>> columns = getColumnsForTable(conn, schema, table);
                for (Map<String, String> column : columns) {
                    String comment = column.get("comment");
                    if (comment != null && !comment.isBlank()) {
                        String enumFqn = generateEnumIfApplicable(comment, column.get("name"), basePackageName, domainCoreMainJava);
                        if (enumFqn != null) {
                            columnToEnumMap.put(table + "." + column.get("name"), enumFqn);
                        }
                    }
                }
            }

            for (String table : tables) {
                String classNamePrefix = toPascalCase(table);
                String extendsClass;
                if (tableEntityTypes != null && tableEntityTypes.containsKey(table)) {
                    extendsClass = tableEntityTypes.get(table);
                } else {
                    extendsClass = aggregateRoots.contains(table) ? "AggregateRoot" : "BaseDomainEntity";
                }

                Path valueObjectDir = Paths.get(domainCoreMainJava.toString(), "valueobject");
                Files.createDirectories(valueObjectDir);
                String idClassName = classNamePrefix + "Id";
                String idClassContent = generateIdClassContent(basePackageName, idClassName);
                Files.write(Paths.get(valueObjectDir.toString(), idClassName + ".java"), idClassContent.getBytes());

                List<Map<String, String>> columns = getColumnsForTable(conn, schema, table);
                Path entityDir = Paths.get(domainCoreMainJava.toString(), "entity");
                Files.createDirectories(entityDir);
                String domainEntityClassName = classNamePrefix + "DomainEntity";
                String domainEntityClassContent = generateDomainEntityClassContent(basePackageName, domainEntityClassName, idClassName, columns, extendsClass, columnToEnumMap, table, detailedForeignKeys, aggregateRoots);
                Files.write(Paths.get(entityDir.toString(), domainEntityClassName + ".java"), domainEntityClassContent.getBytes());
            }
        } catch (SQLException | IOException e) {
            e.printStackTrace();
        }
    }

    private String generateEnumIfApplicable(String comment, String columnName, String basePackageName, Path domainCoreMainJava) throws IOException {
        if (comment == null || !comment.startsWith("Enum:")) {
            return null;
        }

        String spec = comment.substring("Enum:".length());
        String enumClassName;
        String valuesPart;

        int braceStart = spec.indexOf('{');
        if (braceStart == -1 || !spec.endsWith("}")) {
            return null;
        }

        String classNamePart = spec.substring(0, braceStart).trim();
        enumClassName = !classNamePart.isEmpty() ? classNamePart : toPascalCase(columnName);

        valuesPart = spec.substring(braceStart + 1, spec.length() - 1);
        if (valuesPart.trim().isEmpty()) {
            return null;
        }

        StringBuilder enumValues = new StringBuilder();
        int nextOrdinalToAssign = 1;
        boolean first = true;

        for (String def : valuesPart.split(";")) {
            def = def.trim();
            if (def.isEmpty()) continue;

            String enumConstantName;
            int ordinal;

            if (def.contains("-")) {
                String[] parts = def.split("-", 2);
                try {
                    ordinal = Integer.parseInt(parts[0].trim());
                    enumConstantName = parts[1].trim().replaceAll("[^a-zA-Z0-9_]", "_").toUpperCase(Locale.ENGLISH);
                    nextOrdinalToAssign = ordinal + 1;
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    continue; // Skip invalid entries
                }
            } else {
                ordinal = nextOrdinalToAssign;
                enumConstantName = def.trim().replaceAll("[^a-zA-Z0-9_]", "_").toUpperCase(Locale.ENGLISH);
                nextOrdinalToAssign++;
            }

            if (!first) {
                enumValues.append(",\n");
            }
            enumValues.append(String.format("    %s(%d)", enumConstantName, ordinal));
            first = false;
        }

        if (enumValues.length() == 0) {
            return null;
        }

        String enumContent = String.format("""
package %s.domain.core.valueobject;

import java.util.stream.Stream;

public enum %s {
%s;

    private final int value;

    %s(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static %s fromValue(int value) {
        return Stream.of(%s.values())
                .filter(targetEnum -> targetEnum.value == value)
                .findFirst()
                .orElse(null);
    }
}
""", basePackageName, enumClassName, enumValues.toString(), enumClassName, enumClassName, enumClassName);

        Path valueObjectDir = Paths.get(domainCoreMainJava.toString(), "valueobject");
        Files.createDirectories(valueObjectDir);
        Files.write(Paths.get(valueObjectDir.toString(), enumClassName + ".java"), enumContent.getBytes());

        return basePackageName + ".domain.core.valueobject." + enumClassName;
    }

    private String generateIdClassContent(String basePackageName, String idClassName) {
        return String.format("""
package %s.domain.core.valueobject;

import java.util.UUID;

public class %s extends BaseId<UUID> {

    public %s(UUID value) {
        super(value);
    }
}
""", basePackageName, idClassName, idClassName);
    }

    private String generateDomainEntityClassContent(String basePackageName, String domainEntityClassName, String idClassName, List<Map<String, String>> columns, String extendsClass, Map<String, String> columnToEnumMap, String currentTable, Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys, Set<String> aggregateRoots) {
        StringBuilder fields = new StringBuilder();
        StringBuilder constructorParams = new StringBuilder();
        StringBuilder constructorBody = new StringBuilder();
        StringBuilder getters = new StringBuilder();
        Set<String> imports = new TreeSet<>();

        imports.add(String.format("%s.domain.core.valueobject.%s", basePackageName, idClassName));

        constructorParams.append(String.format("%s id, ", idClassName));
        if (extendsClass.equals("AggregateRoot")) {
            constructorBody.append(String.format("        super.setId(id);%n"));
        } else {
            constructorBody.append(String.format("        this.setId(id);%n"));
        }

        Map<String, ForeignKeyInfo> tableForeignKeys = detailedForeignKeys.getOrDefault(currentTable, new HashMap<>());
        String classNamePrefix = domainEntityClassName.replace("DomainEntity", "");
        String fieldPrefix = classNamePrefix.substring(0, 1).toLowerCase(Locale.ENGLISH) + classNamePrefix.substring(1);

        for (Map<String, String> column : columns) {
            String columnName = column.get("name");
            if (!columnName.equals("id")) {
                String camelCaseName = toCamelCase(columnName);
                String fieldName;
                if (JAVA_KEYWORDS.contains(camelCaseName)) {
                    fieldName = fieldPrefix + toPascalCase(columnName);
                } else {
                    fieldName = camelCaseName;
                }

                String fqnFieldType;
                String columnIdentifier = currentTable + "." + columnName;

                if (columnToEnumMap.containsKey(columnIdentifier)) {
                    fqnFieldType = columnToEnumMap.get(columnIdentifier);
                } else {
                    ForeignKeyInfo fkInfo = tableForeignKeys.get(columnName);
                    if (fkInfo != null && aggregateRoots.contains(fkInfo.getPkTableName())) {
                        String referencedEntity = toPascalCase(fkInfo.getPkTableName());
                        fqnFieldType = basePackageName + ".domain.core.valueobject." + referencedEntity + "Id";
                    } else {
                        fqnFieldType = toJavaType(column.get("type"));
                    }
                }

                String simpleFieldType;
                if (fqnFieldType.contains(".")) {
                    imports.add(fqnFieldType);
                    simpleFieldType = fqnFieldType.substring(fqnFieldType.lastIndexOf('.') + 1);
                } else {
                    simpleFieldType = fqnFieldType;
                }

                fields.append(String.format("    private final %s %s;%n", simpleFieldType, fieldName));
                constructorParams.append(String.format("%s %s, ", simpleFieldType, fieldName));
                constructorBody.append(String.format("        this.%s = %s;%n", fieldName, fieldName));

                String getterMethodName;
                if (simpleFieldType.equals("Boolean")) {
                    if (fieldName.startsWith("is") && fieldName.length() > 2 && Character.isUpperCase(fieldName.charAt(2))) {
                        getterMethodName = fieldName;
                    } else {
                        getterMethodName = "is" + toPascalCase(columnName);
                    }
                } else {
                    getterMethodName = "get" + toPascalCase(columnName);
                }
                getters.append(String.format("    public %s %s() {%n        return %s;%n    }%n%n", simpleFieldType, getterMethodName, fieldName));
            }
        }

        if (constructorParams.length() > 0) {
            constructorParams.setLength(constructorParams.length() - 2); // Remove last ", "
        }

        StringBuilder importStatements = new StringBuilder();
        for (String imp : imports) {
            importStatements.append(String.format("import %s;%n", imp));
        }

        return String.format("""
package %s.domain.core.entity;

%s
public class %s extends %s<%s> {

%s
    public %s(%s) {
%s    }

%s}
""", basePackageName, importStatements.toString(), domainEntityClassName, extendsClass, idClassName, fields.toString(), domainEntityClassName, constructorParams.toString(), constructorBody.toString(), getters.toString());
    }

    private List<String> getTables(Connection conn, String schema) throws SQLException {
        List<String> tables = new ArrayList<>();
        String query = "SELECT table_name FROM information_schema.tables WHERE table_schema = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, schema);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString("table_name"));
                }
            }
        }
        return tables;
    }

    public Map<String, Set<String>> getForeignKeys(Connection conn, String schema) throws SQLException {
        Map<String, Set<String>> foreignKeys = new HashMap<>();
        Map<String, Map<String, ForeignKeyInfo>> detailedForeignKeys = getDetailedForeignKeys(conn, schema);
        
        for (Map.Entry<String, Map<String, ForeignKeyInfo>> entry : detailedForeignKeys.entrySet()) {
            Set<String> referencedTables = entry.getValue().values().stream()
                .map(ForeignKeyInfo::getPkTableName)
                .collect(Collectors.toSet());
            foreignKeys.put(entry.getKey(), referencedTables);
        }
        
        return foreignKeys;
    }

    private static class ForeignKeyInfo {
        private final String pkTableName;
        private final String fkColumnName;
        private final String pkColumnName;

        public ForeignKeyInfo(String pkTableName, String fkColumnName, String pkColumnName) {
            this.pkTableName = pkTableName;
            this.fkColumnName = fkColumnName;
            this.pkColumnName = pkColumnName;
        }

        public String getPkTableName() {
            return pkTableName;
        }

        public String getFkColumnName() {
            return fkColumnName;
        }

        public String getPkColumnName() {
            return pkColumnName;
        }
    }

    private Map<String, Map<String, ForeignKeyInfo>> getDetailedForeignKeys(Connection conn, String schema) throws SQLException {
        Map<String, Map<String, ForeignKeyInfo>> foreignKeys = new HashMap<>();
        DatabaseMetaData metaData = conn.getMetaData();
        List<String> allTables = getTables(conn, schema);
        
        for (String tableName : allTables) {
            try (ResultSet rs = metaData.getImportedKeys(conn.getCatalog(), schema, tableName)) {
                while (rs.next()) {
                    String pkTableName = rs.getString("PKTABLE_NAME");
                    String fkColumnName = rs.getString("FKCOLUMN_NAME");
                    String pkColumnName = rs.getString("PKCOLUMN_NAME");
                    
                    foreignKeys
                        .computeIfAbsent(tableName, k -> new HashMap<>())
                        .put(fkColumnName, new ForeignKeyInfo(pkTableName, fkColumnName, pkColumnName));
                }
            }
        }
        return foreignKeys;
    }

    public Set<String> determineAggregateRoots(List<String> allTables, Map<String, Set<String>> foreignKeys) {
        Set<String> aggregateRoots = new HashSet<>();
        // Rule 1: Tables with no outgoing FKs are roots.
        for (String table : allTables) {
            if (!foreignKeys.containsKey(table)) {
                aggregateRoots.add(table);
            }
        }

        // Rule 2: Iteratively find tables that only point to existing roots.
        boolean changed;
        do {
            changed = false;
            for (String table : allTables) {
                if (!aggregateRoots.contains(table) && foreignKeys.containsKey(table)) {
                    if (aggregateRoots.containsAll(foreignKeys.get(table))) {
                        aggregateRoots.add(table);
                        changed = true;
                    }
                }
            }
        } while (changed);

        return aggregateRoots;
    }

    private List<Map<String, String>> getColumnsForTable(Connection conn, String schema, String table) throws SQLException {
        List<Map<String, String>> columns = new ArrayList<>();
        String query = "SELECT c.column_name, c.data_type, pgd.description " +
                "FROM information_schema.columns AS c " +
                "LEFT JOIN pg_catalog.pg_class AS pgc ON pgc.relname = c.table_name AND pgc.relkind = 'r' " +
                "LEFT JOIN pg_catalog.pg_namespace AS pgns ON pgns.oid = pgc.relnamespace AND pgns.nspname = c.table_schema " +
                "LEFT JOIN pg_catalog.pg_description AS pgd ON pgd.objoid = pgc.oid AND pgd.objsubid = c.ordinal_position " +
                "WHERE c.table_schema = ? AND c.table_name = ? " +
                "ORDER BY c.ordinal_position";
        try (PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, schema);
            pstmt.setString(2, table);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> columnData = new HashMap<>();
                    columnData.put("name", rs.getString("column_name"));
                    columnData.put("type", rs.getString("data_type"));
                    columnData.put("comment", rs.getString("description"));
                    columns.add(columnData);
                }
            }
        }
        return columns;
    }

    private String toPascalCase(String s) {
        return Arrays.stream(s.split("_"))
                .filter(part -> part != null && !part.isEmpty())
                .map(part -> part.substring(0, 1).toUpperCase(Locale.ENGLISH) + part.substring(1).toLowerCase(Locale.ENGLISH))
                .collect(Collectors.joining());
    }

    private String toCamelCase(String s) {
        String pascalCase = toPascalCase(s);
        return pascalCase.substring(0, 1).toLowerCase(Locale.ENGLISH) + pascalCase.substring(1);
    }

    private String toJavaType(String dbType) {
        switch (dbType.toLowerCase(Locale.ENGLISH)) {
            case "uuid":
                return "java.util.UUID";
            case "character varying":
            case "varchar":
            case "text":
            case "bpchar":
            case "character":
                return "String";
            case "jsonb":
                return "String"; // Representing jsonb as String is a safe default
            case "timestamp without time zone":
            case "timestamp":
                return "java.time.LocalDateTime";
            case "timestamp with time zone":
                return "java.time.ZonedDateTime";
            case "date":
                return "java.time.LocalDate";
            case "numeric":
                return "java.math.BigDecimal";
            case "int2":
            case "smallint":
                return "Short";
            case "int4":
            case "integer":
                return "Integer";
            case "int8":
            case "bigint":
                return "Long";
            case "boolean":
            case "bool":
                return "Boolean";
            default:
                return "Object";
        }
    }

    private static final Set<String> JAVA_KEYWORDS = new HashSet<>(Arrays.asList(
            "abstract", "continue", "for", "new", "switch", "assert", "default", "goto", "package", "synchronized",
            "boolean", "do", "if", "private", "this", "break", "double", "implements", "protected", "throw",
            "byte", "else", "import", "public", "throws", "case", "enum", "instanceof", "return", "transient",
            "catch", "extends", "int", "short", "try", "char", "final", "interface", "static", "void",
            "class", "finally", "long", "strictfp", "volatile", "const", "float", "native", "super", "while",
            "true", "false", "null"
    ));
}
