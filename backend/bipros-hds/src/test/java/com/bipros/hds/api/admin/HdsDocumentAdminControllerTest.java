package com.bipros.hds.api.admin;

import com.bipros.hds.application.library.HdsLibraryService;
import com.bipros.hds.domain.HdsDocument;
import com.bipros.hds.domain.enums.HdsDiscipline;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke-test for {@link HdsDocumentAdminController} — exercises Spring MVC binding,
 * JSON serialization, and the {@code ApiResponse} envelope contract.
 *
 * <p>{@link SecurityAutoConfiguration} is excluded and {@code addFilters=false} so the test
 * doesn't need {@code spring-security-test} (not on the bipros-hds classpath). Method security
 * via {@code @PreAuthorize} is enabled by {@code bipros-security}, which is not loaded under
 * {@code @WebMvcTest}, so the guards are inert here by design — RBAC behaviour is exercised
 * end-to-end in {@code SecurityIT} (bipros-api).
 *
 * <p>{@code bipros-hds} is a library module with no {@code @SpringBootApplication} entry point,
 * so a nested {@link TestConfig} declares a {@link SpringBootConfiguration} for the test slice
 * to anchor on.
 */
@WebMvcTest(controllers = HdsDocumentAdminController.class,
            excludeAutoConfiguration = SecurityAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
class HdsDocumentAdminControllerTest {

    /**
     * Library-module test anchor: bipros-hds has no {@code @SpringBootApplication}, so the
     * test slice can't auto-locate a config. {@link Import} explicitly registers the controller
     * since the nested config's package isn't scanned by default in this slice.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = SecurityAutoConfiguration.class)
    @Import(HdsDocumentAdminController.class)
    static class TestConfig { }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @MockBean HdsLibraryService library;

    @Test
    void createsDocument() throws Exception {
        var doc = HdsDocument.builder()
            .title("HDS V3")
            .shortCode("HDS-V3")
            .discipline(HdsDiscipline.HIGHWAY)
            .build();
        doc.setId(UUID.randomUUID());
        when(library.createDocument(any())).thenReturn(doc);

        mvc.perform(post("/v1/hds/admin/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                    "title", "HDS V3",
                    "shortCode", "HDS-V3",
                    "discipline", "HIGHWAY"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.shortCode").value("HDS-V3"))
            .andExpect(jsonPath("$.data.title").value("HDS V3"))
            .andExpect(jsonPath("$.data.discipline").value("HIGHWAY"));
    }

    @Test
    void createRejectsBlankTitle() throws Exception {
        // @NotBlank on title — controller validation should short-circuit the service call.
        mvc.perform(post("/v1/hds/admin/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                    "title", "",
                    "shortCode", "HDS-V3",
                    "discipline", "HIGHWAY"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listsDocuments() throws Exception {
        var doc = HdsDocument.builder()
            .title("HDS V2")
            .shortCode("HDS-V2")
            .discipline(HdsDiscipline.BRIDGE)
            .build();
        doc.setId(UUID.randomUUID());
        when(library.listDocuments()).thenReturn(List.of(doc));

        mvc.perform(get("/v1/hds/admin/documents"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].shortCode").value("HDS-V2"))
            .andExpect(jsonPath("$.data[0].discipline").value("BRIDGE"));
    }

    @Test
    void deletesDocument() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(delete("/v1/hds/admin/documents/" + id))
            .andExpect(status().isOk());
    }
}
