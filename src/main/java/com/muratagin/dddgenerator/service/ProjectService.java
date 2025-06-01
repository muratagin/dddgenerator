package com.muratagin.dddgenerator.service;

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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.List;

@Service
public class ProjectService {

    private static final String DEFAULT_VERSION = "0.0.1-SNAPSHOT";

    public byte[] generateProjectZip(ProjectRequest request) throws IOException {
        // Validate CrossCuttingLibrary early
        CrossCuttingLibraryRequest crossCuttingLib = request.getCrossCuttingLibrary();
        boolean useCrossCuttingLibrary = false;
        if (crossCuttingLib != null && 
            crossCuttingLib.getName() != null && !crossCuttingLib.getName().isEmpty() &&
            crossCuttingLib.getGroupId() != null && !crossCuttingLib.getGroupId().isEmpty() &&
            crossCuttingLib.getVersion() != null && !crossCuttingLib.getVersion().isEmpty() &&
            crossCuttingLib.getDependencies() != null && !crossCuttingLib.getDependencies().isEmpty()) {
            
            useCrossCuttingLibrary = true; // Assume valid unless checks fail
            List<String> requiredDeps = Arrays.asList("domain", "application", "persistence");
            Set<String> providedDeps = new HashSet<>(crossCuttingLib.getDependencies());
            if (!providedDeps.containsAll(requiredDeps)) {
                throw new IllegalArgumentException("Cross-cutting library dependencies must include 'domain', 'application', and 'persistence'.");
            }
        } else if (crossCuttingLib != null && 
                   ( (crossCuttingLib.getName() != null && !crossCuttingLib.getName().isEmpty()) || 
                     (crossCuttingLib.getGroupId() != null && !crossCuttingLib.getGroupId().isEmpty()) || 
                     (crossCuttingLib.getVersion() != null && !crossCuttingLib.getVersion().isEmpty()) || 
                     (crossCuttingLib.getDependencies() != null && !crossCuttingLib.getDependencies().isEmpty()) )) {
            // If any part of crossCuttingLibrary is specified, but not all, it's an error
            throw new IllegalArgumentException("Cross-cutting library details (name, groupId, version, dependencies) are incomplete or invalid.");
        }
        // If crossCuttingLib is null or all its fields are null/empty, useCrossCuttingLibrary remains false, and defaults will be used.

        Path tempDir = Files.createTempDirectory("ddd-project-" + request.getArtifactId() + "-");
        String rootArtifactId = request.getArtifactId();
        String groupId = request.getGroupId();
        String version = (request.getVersion() != null && !request.getVersion().isEmpty()) ? request.getVersion() : DEFAULT_VERSION;
        String description = request.getDescription();
        
        // Sanitize package name: replace hyphens with underscores for Java compatibility
        String originalPackageName = request.getPackageName();
        String sanitizedPackageName = originalPackageName.replace('-', '_');

        String basePackagePath = sanitizedPackageName.replace('.', File.separatorChar);
        String basePackageNameForClassGen = sanitizedPackageName; // Use sanitized version for class gen

        // 1. Create Root Project Structure
        Path pomFile = Paths.get(tempDir.toString(), "pom.xml");
        Files.writeString(pomFile, generateRootPomXmlContent(request, version));

        // 2. Create Container Module
        String containerArtifactId = rootArtifactId + "-container";
        Path containerModuleDir = Paths.get(tempDir.toString(), containerArtifactId);
        Files.createDirectories(containerModuleDir);
        Path containerPom = Paths.get(containerModuleDir.toString(), "pom.xml");
        Files.writeString(containerPom, generateContainerPomXmlContent(request, containerArtifactId, rootArtifactId, version));
        Path containerMainJavaDir = Paths.get(containerModuleDir.toString(), "src", "main", "java", basePackagePath, "container");
        Files.createDirectories(containerMainJavaDir);
        Path containerAppFile = Paths.get(containerMainJavaDir.toString(), capitalize(rootArtifactId) + "ContainerApplication.java");
        Files.writeString(containerAppFile, generateContainerApplicationJavaContent(request, rootArtifactId, "container"));
        Path containerResources = Paths.get(containerModuleDir.toString(), "src", "main", "resources");
        Files.createDirectories(containerResources);
        Path applicationYml = Paths.get(containerResources.toString(), "application.yml");
        Files.writeString(applicationYml, generateApplicationYmlContent(rootArtifactId));

        // Create profile-specific application.yml files
        List<String> profiles = Arrays.asList("local", "dev", "test", "uat", "prod");
        for (String profile : profiles) {
            Path profileApplicationYml = Paths.get(containerResources.toString(), "application-" + profile + ".yml");
            Files.writeString(profileApplicationYml, generateProfileApplicationYmlContent(rootArtifactId, profile));
        }

        // 3. Create Domain Module (parent pom for domain-core and application-service)
        String domainParentArtifactId = rootArtifactId + "-domain";
        Path domainModuleDir = Paths.get(tempDir.toString(), domainParentArtifactId);
        Files.createDirectories(domainModuleDir);
        Path domainParentPom = Paths.get(domainModuleDir.toString(), "pom.xml");
        Files.writeString(domainParentPom, generateDomainParentPomXmlContent(request, domainParentArtifactId, rootArtifactId, version));

        // 3.a. Create Domain Core Module
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


        // 3.b. Create Application Service Module
        String appServiceArtifactId = rootArtifactId + "-application-service";
        Path appServiceModuleDir = Paths.get(domainModuleDir.toString(), appServiceArtifactId);
        Files.createDirectories(appServiceModuleDir);
        Path appServicePom = Paths.get(appServiceModuleDir.toString(), "pom.xml");
        Files.writeString(appServicePom, generateApplicationServicePomXmlContent(request, appServiceArtifactId, domainParentArtifactId, domainCoreArtifactId, version));
        Path appServiceMainJava = Paths.get(appServiceModuleDir.toString(), "src", "main", "java", basePackagePath, "domain", "applicationservice");
        Files.createDirectories(appServiceMainJava);
        Files.createFile(Paths.get(appServiceMainJava.toString(), ".gitkeep"));

        // 4. Create Infrastructure Module (parent pom for persistence)
        String infraParentArtifactId = rootArtifactId + "-infrastructure";
        Path infraModuleDir = Paths.get(tempDir.toString(), infraParentArtifactId);
        Files.createDirectories(infraModuleDir);
        Path infraParentPom = Paths.get(infraModuleDir.toString(), "pom.xml");
        Files.writeString(infraParentPom, generateInfrastructureParentPomXmlContent(request, infraParentArtifactId, rootArtifactId, version));

        // 4.a. Create Persistence Module
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

        // 5. Create Application (Presentation) Module
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


        // 6. Zip up the temp directory
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zipOut = new ZipOutputStream(baos)) {
            zipDirectory(tempDir.toFile(), "", zipOut); // Pass "" as parentName for root files
        }

        // 7. Clean up
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
        // Handle hyphens: split, capitalize each part, then join
        String[] parts = str.split("-");
        StringBuilder capitalizedString = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            capitalizedString.append(part.substring(0, 1).toUpperCase()).append(part.substring(1).toLowerCase());
        }
        return capitalizedString.toString();
    }

    private String generateRootPomXmlContent(ProjectRequest request, String effectiveVersion) {
        String artifactId = request.getArtifactId();
        String javaVersion = (request.getJavaVersion() != null && !request.getJavaVersion().isEmpty()) ? request.getJavaVersion() : "21";
        String springBootVersion = (request.getSpringBootVersion() != null && !request.getSpringBootVersion().isEmpty()) ? request.getSpringBootVersion() : "3.5.0";
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
                    "            </dependency>"; // No trailing newline here, it will be handled by the main template
            lombokCompilerPathVersionTag = String.format("                            <version>%s</version>\n", lombokVersionPropertyRef);
        } else {
            // If lombokVersion is not provided, Lombok is not added to root dependencyManagement.
            // Children inherit from Spring Boot parent. Compiler plugin path also inherits.
        }

        StringBuilder crossCuttingDepsXmlBuilder = new StringBuilder();
        CrossCuttingLibraryRequest crossCuttingLib = request.getCrossCuttingLibrary();

        if (crossCuttingLib != null && crossCuttingLib.getName() != null && !crossCuttingLib.getName().isEmpty() &&
            crossCuttingLib.getVersion() != null && !crossCuttingLib.getVersion().isEmpty() &&
            crossCuttingLib.getGroupId() != null && !crossCuttingLib.getGroupId().isEmpty() &&
            crossCuttingLib.getDependencies() != null && !crossCuttingLib.getDependencies().isEmpty()) {
            
            String libName = crossCuttingLib.getName();
            String libVersionProperty = libName.toLowerCase() + ".version";
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
            <!-- Project Modules -->
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
                // Modules
                artifactId, artifactId, artifactId, artifactId,
                // Properties
                propertiesBuilder.toString(),
                // Dependency Management - Project Modules
                request.getGroupId(), artifactId, // container
                request.getGroupId(), artifactId, // application
                request.getGroupId(), artifactId, // domain-core
                request.getGroupId(), artifactId, // application-service
                request.getGroupId(), artifactId, // persistence
                // Common Third-Party Dependencies
                lombokDependencyManagementEntry,
                crossCuttingDepsXmlBuilder.toString(),
                // Lombok version for compiler plugin
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
                <!-- Version is inherited from pluginManagement in root POM -->
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
                request.getGroupId(), // For parent groupId
                rootArtifactId,       // For parent artifactId
                effectiveVersion, // For parent version
                containerArtifactId,  // For self artifactId
                sanitizedPackageName, // For start-class package name part
                capitalize(rootArtifactId), // For start-class ApplicationName part
                request.getGroupId(), rootArtifactId, // domain-core module
                request.getGroupId(), rootArtifactId, // application-service module
                request.getGroupId(), rootArtifactId, // application module
                request.getGroupId(), rootArtifactId  // persistence module
        );
    }
    private String generateContainerApplicationJavaContent(ProjectRequest request, String rootArtifactId, String modulePackageName) {
        String mainClassName = capitalize(rootArtifactId) + capitalize(modulePackageName) + "Application";
        String sanitizedPackageName = request.getPackageName().replace('-', '_');
        // Use request.getPackageName() for the main package and ComponentScan
        return String.format("""
package %s.%s;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "%s") // Scan all project modules based on request.getPackageName()
public class %s {

    public static void main(String[] args) {
        SpringApplication.run(%s.class, args);
    }

}
                """,
                sanitizedPackageName, // e.g., com.example.demo
                modulePackageName.replace("-", ""), // e.g., container
                sanitizedPackageName, // scan base package: e.g., com.example.demo
                mainClassName,
                mainClassName
        );
    }

    private String generateApplicationYmlContent(String rootArtifactId) {
        return String.format("""
# Default application configuration
# Activate a specific profile by setting the SPRING_PROFILES_ACTIVE environment variable
# or by adding --spring.profiles.active={profile} to the command line arguments.
# Example: java -jar your-app.jar --spring.profiles.active=dev

server:
  port: 8080

spring:
  application:
    name: %s
# Common datasource example (can be overridden in profile-specific files)
#  datasource:
#    url: jdbc:postgresql://localhost:5432/%sdb
#    username: user
#    password: password
#    driver-class-name: org.postgresql.Driver
#  jpa:
#    hibernate:
#      ddl-auto: update # For dev; use "validate" or "none" in prod
#    show-sql: true
#    properties:
#      hibernate:
#        format_sql: true
""", rootArtifactId, rootArtifactId);
    }

    private String generateProfileApplicationYmlContent(String rootArtifactId, String profile) {
        String databaseNameSuffix = profile.equals("local") ? "db" : profile + "db";
        String ddlAuto = "none";
        if (profile.equals("local") || profile.equals("dev")) {
            ddlAuto = "update";
        } else if (profile.equals("test")) {
            ddlAuto = "create-drop";
        }

        return String.format("""
# Configuration for %s environment
spring:
  application:
    name: %s-%s
  datasource:
    url: jdbc:postgresql://localhost:5432/%s%s
    username: %s_user
    password: password # Change in a real scenario
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: %s
    show-sql: %s
    properties:
      hibernate:
        format_sql: %s

environment:
  name: %s
""", profile, rootArtifactId, profile, rootArtifactId, databaseNameSuffix, profile, ddlAuto, 
     (profile.equals("local") || profile.equals("dev") || profile.equals("test") ? "true" : "false"),
     (profile.equals("local") || profile.equals("dev") || profile.equals("test") ? "true" : "false"),
     profile);
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
                rootArtifactId, // domain-core module name uses root artifactId as prefix
                rootArtifactId  // application-service module name uses root artifactId as prefix
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
            <!-- Scope and version managed by root POM -->
        </dependency>

        <!-- Jackson Annotations (often used with DTOs) -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
            <!-- Version managed by Spring Boot parent -->
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
                rootArtifactId // persistence module name uses root artifactId as prefix
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
            <!-- Scope and version managed by root POM -->
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
            <!-- Scope and version managed by root POM -->
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
                .sorted((o1, o2) -> o2.compareTo(o1)) // Reverse order for deletion
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        // Log error or throw a custom exception
                        System.err.println("Failed to delete: " + p + " - " + e.getMessage());
                    }
                });
    }

    // --- Methods for generating default cross-cutting concern classes ---

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
        // Note: The original BaseEntity used jakarta.persistence.
        // Ensure these dependencies are available in the persistence module if this is generated.
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
