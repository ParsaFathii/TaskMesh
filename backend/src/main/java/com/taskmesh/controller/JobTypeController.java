package com.taskmesh.controller;

import com.taskmesh.catalog.JobTypeCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Job type catalog (any authenticated role): payload schemas and examples for the
 * seven supported job types.
 */
@RestController
@RequestMapping("/api/v1/job-types")
public class JobTypeController {

    private final JobTypeCatalog catalog;

    public JobTypeController(JobTypeCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<Map<String, Object>> jobTypes() {
        return catalog.all().stream()
                .map(spec -> spec.describe())
                .toList();
    }
}
