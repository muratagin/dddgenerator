package com.muratagin.dddgenerator.service;

import com.muratagin.dddgenerator.dto.ProjectRequest;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ProjectService {

    public byte[] generateProjectZip(ProjectRequest request) throws IOException {
        // 1. Create a temp directory
        Path tempDir = Files.createTempDirectory("tempProject-");

        // 2. Create basic structure: /src/main/java/ + package
        //    e.g. groupId = com.example => directories = ["com", "example"]
        String packagePath = request.getGroupId().replace('.', File.separatorChar);

        Path mainJava = Paths.get(tempDir.toString(), "src", "main", "java", packagePath);
        Files.createDirectories(mainJava);

        // 3. Generate a pom.xml at the root of tempDir
        Path pomFile = Paths.get(tempDir.toString(), "pom.xml");
        Files.writeString(pomFile, generatePomXmlContent(request));

        // 4. Generate an Application.java at the /src/main/java/... path
        Path applicationFile = Paths.get(mainJava.toString(), "Application.java");
        Files.writeString(applicationFile, generateApplicationJavaContent(request));

        // 5. Optionally create more structure (test folder, resources, etc.)
        // ...

        // 6. Zip up the temp directory
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zipOut = new ZipOutputStream(baos)) {
            zipDirectory(tempDir.toFile(), tempDir.toFile().getName(), zipOut);
        }

        // 7. Clean up the temp directory if desired (e.g., using Files.delete)
        //    but be careful with directories; see example:
        //    deleteDirectoryRecursively(tempDir);

        return baos.toByteArray();
    }

    /**
     * Recursively zip a directory (including subdirectories)
     */
    private void zipDirectory(File folderToZip, String parentName, ZipOutputStream zipOut) throws IOException {
        File[] children = folderToZip.listFiles();
        if (children == null) {
            return;
        }

        for (File file : children) {
            String zipEntryName = parentName + "/" + file.getName();
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

    /**
     * Generate the content of pom.xml as a String
     */
    private String generatePomXmlContent(ProjectRequest request) {
        // You can customize dependencies further using request.getDependencies()
        return String.format("""
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                    <name>%s</name>
                    <description>%s</description>
                    <properties>
                        <java.version>17</java.version>
                        <spring.boot.version>3.0.0</spring.boot.version>
                    </properties>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-dependencies</artifactId>
                                <version>${spring.boot.version}</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter</artifactId>
                        </dependency>
                        <!-- Additional dependencies from request.getDependencies() can be appended here -->
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """,
                request.getGroupId(),
                request.getArtifactId(),
                request.getVersion(),
                request.getArtifactId(),
                request.getDescription()
        );
    }

    /**
     * Generate Application.java content
     */
    private String generateApplicationJavaContent(ProjectRequest request) {
        // The package is request.getGroupId(), plus we place "Application" as class name
        return "package " + request.getGroupId() + ";\n\n" +
                "import org.springframework.boot.SpringApplication;\n" +
                "import org.springframework.boot.autoconfigure.SpringBootApplication;\n\n" +
                "@SpringBootApplication\n" +
                "public class Application {\n" +
                "    public static void main(String[] args) {\n" +
                "        SpringApplication.run(Application.class, args);\n" +
                "    }\n" +
                "}\n";
    }

    /**
     * Helper method to delete directory recursively
     */
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
                        e.printStackTrace();
                    }
                });
    }
}
