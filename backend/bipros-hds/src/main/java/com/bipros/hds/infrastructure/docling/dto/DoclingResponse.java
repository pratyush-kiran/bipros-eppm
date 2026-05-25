package com.bipros.hds.infrastructure.docling.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DoclingResponse {
    private String status;
    private Integer pages;
    private List<DoclingBlock> blocks;
}
