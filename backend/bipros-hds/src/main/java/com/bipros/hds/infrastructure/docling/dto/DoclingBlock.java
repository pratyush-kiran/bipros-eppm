package com.bipros.hds.infrastructure.docling.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DoclingBlock {
    /** "heading", "paragraph", "table", "figure", "list_item", etc. */
    private String type;
    private Integer level;          // for headings
    private Integer page;
    private String text;            // for paragraph / list_item / figure caption
    private String markdown;        // for table (markdown table dump)
    private String sectionNumber;   // best-effort, e.g. "4.3.2"
}
