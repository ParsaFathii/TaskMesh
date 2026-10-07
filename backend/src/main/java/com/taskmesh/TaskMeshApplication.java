package com.taskmesh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the TaskMesh backend (REST API, job orchestration, event fan-out).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TaskMeshApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskMeshApplication.class, args);
    }
}
