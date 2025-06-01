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
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ProjectService {

    public byte[] generateProjectZip(ProjectRequest request) throws IOException {
        Path tempDir = Files.createTempDirectory("ddd-project-" + request.getArtifactId() + "-");
        String rootArtifactId = request.getArtifactId();
        String groupId = request.getGroupId();
        String version = request.getVersion();
        String description = request.getDescription();
        String basePackagePath = groupId.replace('.', File.separatorChar);

        // 1. Create Root Project Structure
        Path pomFile = Paths.get(tempDir.toString(), "pom.xml");
        Files.writeString(pomFile, generateRootPomXmlContent(request));

        // 2. Create Container Module
        String containerArtifactId = rootArtifactId + "-container";
        Path containerModuleDir = Paths.get(tempDir.toString(), containerArtifactId);
        Files.createDirectories(containerModuleDir);
        Path containerPom = Paths.get(containerModuleDir.toString(), "pom.xml");
        Files.writeString(containerPom, generateContainerPomXmlContent(request, containerArtifactId, rootArtifactId));
        Path containerMainJavaDir = Paths.get(containerModuleDir.toString(), "src", "main", "java", basePackagePath, "container");
        Files.createDirectories(containerMainJavaDir);
        Path containerAppFile = Paths.get(containerMainJavaDir.toString(), capitalize(rootArtifactId) + "ContainerApplication.java");
        Files.writeString(containerAppFile, generateContainerApplicationJavaContent(request, rootArtifactId, "container"));
        Path containerResources = Paths.get(containerModuleDir.toString(), "src", "main", "resources");
        Files.createDirectories(containerResources);
        Path applicationYml = Paths.get(containerResources.toString(), "application.yml");
        Files.writeString(applicationYml, generateApplicationYmlContent(rootArtifactId));

        // 3. Create Domain Module (parent pom for domain-core and application-service)
        String domainParentArtifactId = rootArtifactId + "-domain";
        Path domainModuleDir = Paths.get(tempDir.toString(), domainParentArtifactId);
        Files.createDirectories(domainModuleDir);
        Path domainParentPom = Paths.get(domainModuleDir.toString(), "pom.xml");
        Files.writeString(domainParentPom, generateDomainParentPomXmlContent(request, domainParentArtifactId, rootArtifactId));

        // 3.a. Create Domain Core Module
        String domainCoreArtifactId = rootArtifactId + "-domain-core";
        Path domainCoreModuleDir = Paths.get(domainModuleDir.toString(), domainCoreArtifactId);
        Files.createDirectories(domainCoreModuleDir);
        Path domainCorePom = Paths.get(domainCoreModuleDir.toString(), "pom.xml");
        Files.writeString(domainCorePom, generateDomainCorePomXmlContent(request, domainCoreArtifactId, domainParentArtifactId));
        Path domainCoreMainJava = Paths.get(domainCoreModuleDir.toString(), "src", "main", "java", basePackagePath, "domain", "core");
        Files.createDirectories(domainCoreMainJava);
        Files.createFile(Paths.get(domainCoreMainJava.toString(), ".gitkeep"));


        // 3.b. Create Application Service Module
        String appServiceArtifactId = rootArtifactId + "-application-service";
        Path appServiceModuleDir = Paths.get(domainModuleDir.toString(), appServiceArtifactId);
        Files.createDirectories(appServiceModuleDir);
        Path appServicePom = Paths.get(appServiceModuleDir.toString(), "pom.xml");
        Files.writeString(appServicePom, generateApplicationServicePomXmlContent(request, appServiceArtifactId, domainParentArtifactId, domainCoreArtifactId));
        Path appServiceMainJava = Paths.get(appServiceModuleDir.toString(), "src", "main", "java", basePackagePath, "domain", "applicationservice");
        Files.createDirectories(appServiceMainJava);
        Files.createFile(Paths.get(appServiceMainJava.toString(), ".gitkeep"));

        // 4. Create Infrastructure Module (parent pom for persistence)
        String infraParentArtifactId = rootArtifactId + "-infrastructure";
        Path infraModuleDir = Paths.get(tempDir.toString(), infraParentArtifactId);
        Files.createDirectories(infraModuleDir);
        Path infraParentPom = Paths.get(infraModuleDir.toString(), "pom.xml");
        Files.writeString(infraParentPom, generateInfrastructureParentPomXmlContent(request, infraParentArtifactId, rootArtifactId));

        // 4.a. Create Persistence Module
        String persistenceArtifactId = rootArtifactId + "-persistence";
        Path persistenceModuleDir = Paths.get(infraModuleDir.toString(), persistenceArtifactId);
        Files.createDirectories(persistenceModuleDir);
        Path persistencePom = Paths.get(persistenceModuleDir.toString(), "pom.xml");
        Files.writeString(persistencePom, generatePersistencePomXmlContent(request, persistenceArtifactId, infraParentArtifactId, appServiceArtifactId));
        Path persistenceMainJava = Paths.get(persistenceModuleDir.toString(), "src", "main", "java", basePackagePath, "infrastructure", "persistence");
        Files.createDirectories(persistenceMainJava);
        Files.createFile(Paths.get(persistenceMainJava.toString(), ".gitkeep"));

        // 5. Create Application (Presentation) Module
        String appLayerArtifactId = rootArtifactId + "-application";
        Path appLayerModuleDir = Paths.get(tempDir.toString(), appLayerArtifactId);
        Files.createDirectories(appLayerModuleDir);
        Path appLayerPom = Paths.get(appLayerModuleDir.toString(), "pom.xml");
        Files.writeString(appLayerPom, generateApplicationLayerPomXmlContent(request, appLayerArtifactId, rootArtifactId, appServiceArtifactId));
        Path appLayerMainJava = Paths.get(appLayerModuleDir.toString(), "src", "main", "java", basePackagePath, "application");
        Files.createDirectories(appLayerMainJava);
        Files.createFile(Paths.get(appLayerMainJava.toString(), ".gitkeep"));


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

    private String generateRootPomXmlContent(ProjectRequest request) {
        String artifactId = request.getArtifactId();
        String javaVersion = (request.getJavaVersion() != null && !request.getJavaVersion().isEmpty()) ? request.getJavaVersion() : "21";
        String springBootVersion = (request.getSpringBootVersion() != null && !request.getSpringBootVersion().isEmpty()) ? request.getSpringBootVersion() : "3.5.0";

        StringBuilder propertiesBuilder = new StringBuilder();
        propertiesBuilder.append(String.format("        <java.version>%s</java.version>\n", javaVersion));
        propertiesBuilder.append("        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n");
        propertiesBuilder.append("        <lombok.version>1.18.30</lombok.version>\n");

        StringBuilder crossCuttingDepsXmlBuilder = new StringBuilder();
        CrossCuttingLibraryRequest crossCuttingLib = request.getCrossCuttingLibrary();

        if (crossCuttingLib != null && crossCuttingLib.getName() != null && !crossCuttingLib.getName().isEmpty() &&
            crossCuttingLib.getVersion() != null && !crossCuttingLib.getVersion().isEmpty() &&
            crossCuttingLib.getGroupId() != null && !crossCuttingLib.getGroupId().isEmpty() &&
            crossCuttingLib.getDependencies() != null && !crossCuttingLib.getDependencies().isEmpty()) {
            
            String libName = crossCuttingLib.getName();
            String libVersionProperty = libName.toLowerCase() + ".version";
            propertiesBuilder.append(String.format("        <%s>%s</%s>\n", libVersionProperty, crossCuttingLib.getVersion(), libVersionProperty));

            crossCuttingDepsXmlBuilder.append("\n            <!-- Cross-Cutting Library: ").append(libName).append(" -->\n");
            for (String depSuffix : crossCuttingLib.getDependencies()) {
                crossCuttingDepsXmlBuilder.append(String.format("""
            <dependency>
                <groupId>%s</groupId>
                <artifactId>%s-%s</artifactId>
                <version>${%s}</version>
            </dependency>
""", crossCuttingLib.getGroupId(), libName, depSuffix, libVersionProperty));
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
            <dependency>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>${lombok.version}</version>
                <scope>provided</scope>
            </dependency>%s
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
                            <version>${lombok.version}</version>
                        </path>
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
                request.getVersion(),
                artifactId,
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
                // Cross-cutting library dependencies
                crossCuttingDepsXmlBuilder.toString()
        );
    }

    private String generateContainerPomXmlContent(ProjectRequest request, String containerArtifactId, String rootArtifactId) {
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
                request.getVersion(), // For parent version
                containerArtifactId,  // For self artifactId
                request.getGroupId(), // For start-class groupId part
                capitalize(rootArtifactId), // For start-class ApplicationName part
                request.getGroupId(), rootArtifactId, // domain-core module
                request.getGroupId(), rootArtifactId, // application-service module
                request.getGroupId(), rootArtifactId, // application module
                request.getGroupId(), rootArtifactId  // persistence module
        );
    }
    private String generateContainerApplicationJavaContent(ProjectRequest request, String rootArtifactId, String modulePackageName) {
        String mainClassName = capitalize(rootArtifactId) + capitalize(modulePackageName) + "Application";
        return String.format("""
package %s.%s;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "%s") // Scan all project modules
public class %s {

    public static void main(String[] args) {
        SpringApplication.run(%s.class, args);
    }

}
                """,
                request.getGroupId(),
                modulePackageName.replace("-", ""), // com.example.container
                request.getGroupId(), // scan base package: com.example
                mainClassName,
                mainClassName
        );
    }

    private String generateApplicationYmlContent(String rootArtifactId) {
        return String.format("""
server:
  port: 8080

spring:
  application:
    name: %s
  datasource: # Basic datasource example for PostgreSQL
    url: jdbc:postgresql://localhost:5432/%sdb
    username: user
    password: password
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: update # For dev; use "validate" or "none" in prod
    show-sql: true
    properties:
      hibernate:
        format_sql: true
""", rootArtifactId, rootArtifactId);
    }

    private String generateDomainParentPomXmlContent(ProjectRequest request, String domainParentArtifactId, String rootArtifactId) {
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
                request.getVersion(),
                domainParentArtifactId,
                rootArtifactId, // domain-core module name uses root artifactId as prefix
                rootArtifactId  // application-service module name uses root artifactId as prefix
        );
    }

    private String generateDomainCorePomXmlContent(ProjectRequest request, String domainCoreArtifactId, String domainParentArtifactId) {
         return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId> <!-- Parent is the -domain module -->
        <version>%s</version>
    </parent>

    <artifactId>%s</artifactId>

</project>
                """,
                request.getGroupId(),
                domainParentArtifactId,
                request.getVersion(),
                domainCoreArtifactId
        );
    }
    
    private String generateApplicationServicePomXmlContent(ProjectRequest request, String appServiceArtifactId, String domainParentArtifactId, String domainCoreArtifactId) {
        return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId> <!-- Parent is the -domain module -->
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
                request.getVersion(),
                appServiceArtifactId,
                request.getGroupId(), domainCoreArtifactId
        );
    }

    private String generateInfrastructureParentPomXmlContent(ProjectRequest request, String infraParentArtifactId, String rootArtifactId) {
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
                request.getVersion(),
                infraParentArtifactId,
                rootArtifactId // persistence module name uses root artifactId as prefix
        );
    }

    private String generatePersistencePomXmlContent(ProjectRequest request, String persistenceArtifactId, String infraParentArtifactId, String appServiceArtifactId) {
        return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId> <!-- Parent is the -infrastructure module -->
        <version>%s</version>
    </parent>

    <artifactId>%s</artifactId>

    <dependencies>
        <dependency>
            <groupId>%s</groupId>
            <artifactId>%s</artifactId> <!-- Depends on Application Service for Ports -->
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
                request.getVersion(),
                persistenceArtifactId,
                request.getGroupId(), appServiceArtifactId
        );
    }

    private String generateApplicationLayerPomXmlContent(ProjectRequest request, String appLayerArtifactId, String rootArtifactId, String appServiceArtifactId) {
        return String.format("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId> <!-- Parent is the root project -->
        <version>%s</version>
    </parent>

    <artifactId>%s</artifactId>

    <dependencies>
        <dependency>
            <groupId>%s</groupId>
            <artifactId>%s</artifactId> <!-- Depends on Application Service -->
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId> <!-- For REST controllers -->
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId> <!-- For request validation -->
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
                request.getVersion(),
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
}
