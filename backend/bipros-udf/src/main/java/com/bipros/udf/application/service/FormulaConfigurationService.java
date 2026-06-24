package com.bipros.udf.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.udf.application.dto.*;
import com.bipros.udf.domain.engine.FormulaAstCache;
import com.bipros.udf.domain.model.FormulaCategory;
import com.bipros.udf.domain.model.FormulaMaster;
import com.bipros.udf.domain.model.FormulaOutputType;
import com.bipros.udf.domain.model.FormulaOverride;
import com.bipros.udf.domain.model.FormulaVersion;
import com.bipros.udf.domain.repository.FormulaMasterRepository;
import com.bipros.udf.domain.repository.FormulaOverrideRepository;
import com.bipros.udf.domain.repository.FormulaVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FormulaConfigurationService {

    private final FormulaMasterRepository formulaMasterRepository;
    private final FormulaOverrideRepository formulaOverrideRepository;
    private final FormulaVersionRepository formulaVersionRepository;
    private final FormulaAstCache formulaAstCache;

    private void validateExpression(String expression, String fieldName) {
        if (expression == null || expression.isBlank()) {
            throw new BusinessRuleException("FORMULA_EXPRESSION_EMPTY",
                    fieldName + " cannot be empty");
        }
        formulaAstCache.validate(expression);
    }

    // ---- Master Formulas ----

    @Transactional
    public FormulaDto createMasterFormula(CreateFormulaRequest request) {
        if (formulaMasterRepository.existsByCode(request.getCode())) {
            throw new BusinessRuleException("FORMULA_CODE_EXISTS",
                    "Formula code already exists: " + request.getCode());
        }
        validateExpression(request.getDefaultExpression(), "defaultExpression");
        FormulaMaster master = mapToEntity(request);
        FormulaMaster saved = formulaMasterRepository.save(master);
        log.info("Created formula master: {} ({})", saved.getCode(), saved.getName());
        return mapToDto(saved);
    }

    @Transactional
    public FormulaDto updateMasterFormula(UUID id, CreateFormulaRequest request) {
        FormulaMaster master = formulaMasterRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FormulaMaster", id));

        if (!master.getCode().equals(request.getCode()) && formulaMasterRepository.existsByCode(request.getCode())) {
            throw new BusinessRuleException("FORMULA_CODE_EXISTS",
                    "Formula code already exists: " + request.getCode());
        }

        validateExpression(request.getDefaultExpression(), "defaultExpression");

        // Save a version snapshot before update
        saveVersion(master, null);

        master.setCode(request.getCode());
        master.setName(request.getName());
        master.setCategory(request.getCategory());
        master.setDescription(request.getDescription());
        master.setDefaultExpression(request.getDefaultExpression());
        master.setInputVariablesJson(request.getInputVariablesJson());
        master.setOutputType(request.getOutputType());
        master.setSortOrder(request.getSortOrder());
        master.setModuleSource(request.getModuleSource());

        // These columns are NOT NULL (or carry meaningful defaults) and are not exposed on the
        // edit form, so the request omits them (arrives null). Preserve the existing values rather
        // than overwriting with null — otherwise editing e.g. just the description would null out
        // is_active/is_editable/rounding_mode and violate the not-null constraints.
        if (request.getScale() != null) master.setScale(request.getScale());
        if (request.getRoundingMode() != null) master.setRoundingMode(request.getRoundingMode());
        if (request.getZeroDefault() != null) master.setZeroDefault(request.getZeroDefault());
        if (request.getIsActive() != null) master.setIsActive(request.getIsActive());
        if (request.getIsEditable() != null) master.setIsEditable(request.getIsEditable());

        FormulaMaster saved = formulaMasterRepository.save(master);
        log.info("Updated formula master: {}", saved.getCode());
        return mapToDto(saved);
    }

    @Transactional(readOnly = true)
    public FormulaDto getMasterFormula(UUID id) {
        return formulaMasterRepository.findById(id)
                .map(this::mapToDto)
                .orElseThrow(() -> new ResourceNotFoundException("FormulaMaster", id));
    }

    @Transactional(readOnly = true)
    public FormulaDto getMasterFormulaByCode(String code) {
        return formulaMasterRepository.findByCode(code)
                .map(this::mapToDto)
                .orElseThrow(() -> new ResourceNotFoundException("FormulaMaster", code));
    }

    @Transactional(readOnly = true)
    public List<FormulaDto> listAllMasterFormulas() {
        return formulaMasterRepository.findAll().stream()
                .sorted(Comparator.comparing(FormulaMaster::getCategory)
                        .thenComparing(FormulaMaster::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(FormulaMaster::getName))
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FormulaDto> listFormulasByCategory(FormulaCategory category) {
        return formulaMasterRepository.findByCategory(category).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FormulaCategoryDto> listFormulasByCategory() {
        return Arrays.stream(FormulaCategory.values())
                .map(cat -> {
                    List<FormulaDto> formulas = listFormulasByCategory(cat);
                    return FormulaCategoryDto.builder()
                            .code(cat.name())
                            .name(cat.name().replace("_", " "))
                            .description("")
                            .formulas(formulas)
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ---- Overrides ----

    @Transactional
    public FormulaOverrideDto createOverride(CreateFormulaOverrideRequest request) {
        // Validate master exists
        FormulaMaster master = formulaMasterRepository.findByCode(request.getFormulaCode())
                .orElseThrow(() -> new ResourceNotFoundException("FormulaMaster", request.getFormulaCode()));

        if (!Boolean.TRUE.equals(master.getIsEditable())) {
            throw new BusinessRuleException("FORMULA_NOT_EDITABLE",
                    "Formula " + request.getFormulaCode() + " is not editable");
        }

        if (formulaOverrideRepository.existsByFormulaCodeAndProjectId(request.getFormulaCode(), request.getProjectId())) {
            throw new BusinessRuleException("OVERRIDE_EXISTS",
                    "Override already exists for formula " + request.getFormulaCode() + " on project " + request.getProjectId());
        }

        validateExpression(request.getOverrideExpression(), "overrideExpression");

        FormulaOverride override = new FormulaOverride();
        override.setFormulaCode(request.getFormulaCode());
        override.setProjectId(request.getProjectId());
        override.setOverrideExpression(request.getOverrideExpression());
        override.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);
        override.setEffectiveFrom(parseDate(request.getEffectiveFrom()));
        override.setEffectiveTo(parseDate(request.getEffectiveTo()));
        override.setOverrideReason(request.getOverrideReason());

        FormulaOverride saved = formulaOverrideRepository.save(override);

        // Save version
        saveVersion(master, saved);

        log.info("Created formula override: {} for project {}", saved.getFormulaCode(), saved.getProjectId());
        return mapToDto(saved);
    }

    @Transactional
    public FormulaOverrideDto updateOverride(UUID id, CreateFormulaOverrideRequest request) {
        FormulaOverride override = formulaOverrideRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FormulaOverride", id));

        FormulaMaster master = formulaMasterRepository.findByCode(override.getFormulaCode())
                .orElseThrow(() -> new ResourceNotFoundException("FormulaMaster", override.getFormulaCode()));

        validateExpression(request.getOverrideExpression(), "overrideExpression");

        // Save version before update
        saveVersion(master, override);

        override.setOverrideExpression(request.getOverrideExpression());
        override.setIsActive(request.getIsActive() != null ? request.getIsActive() : override.getIsActive());
        override.setEffectiveFrom(parseDate(request.getEffectiveFrom()));
        override.setEffectiveTo(parseDate(request.getEffectiveTo()));
        override.setOverrideReason(request.getOverrideReason());

        FormulaOverride saved = formulaOverrideRepository.save(override);
        log.info("Updated formula override: {} for project {}", saved.getFormulaCode(), saved.getProjectId());
        return mapToDto(saved);
    }

    @Transactional
    public void deleteOverride(UUID id) {
        FormulaOverride override = formulaOverrideRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FormulaOverride", id));
        formulaOverrideRepository.delete(override);
        log.info("Deleted formula override: {} for project {}", override.getFormulaCode(), override.getProjectId());
    }

    @Transactional(readOnly = true)
    public List<FormulaOverrideDto> listOverridesByProject(UUID projectId) {
        return formulaOverrideRepository.findByProjectId(projectId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FormulaOverrideDto> listOverridesByFormula(String formulaCode) {
        return formulaOverrideRepository.findByFormulaCode(formulaCode).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    // ---- Versions ----

    @Transactional(readOnly = true)
    public List<FormulaVersion> listVersions(String formulaCode, UUID projectId) {
        if (projectId != null) {
            return formulaVersionRepository.findByFormulaCodeAndProjectIdOrderByVersionNumberDesc(formulaCode, projectId);
        }
        return formulaVersionRepository.findByFormulaCodeOrderByVersionNumberDesc(formulaCode);
    }

    @Transactional
    public FormulaOverrideDto revertToVersion(UUID versionId) {
        FormulaVersion version = formulaVersionRepository.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("FormulaVersion", versionId));

        if (version.getProjectId() != null) {
            // Revert project override
            FormulaOverride override = formulaOverrideRepository
                    .findByFormulaCodeAndProjectId(version.getFormulaCode(), version.getProjectId())
                    .orElseThrow(() -> new ResourceNotFoundException("FormulaOverride",
                            version.getFormulaCode() + "/" + version.getProjectId()));

            override.setOverrideExpression(version.getExpression());
            override.setIsActive(version.getIsActive());
            override.setEffectiveFrom(version.getEffectiveFrom());
            override.setEffectiveTo(version.getEffectiveTo());
            FormulaOverride saved = formulaOverrideRepository.save(override);

            // Create a new version entry for the revert
            FormulaMaster master = formulaMasterRepository.findByCode(version.getFormulaCode())
                    .orElseThrow(() -> new ResourceNotFoundException("FormulaMaster", version.getFormulaCode()));
            saveVersion(master, saved, "Reverted to version " + version.getVersionNumber());

            return mapToDto(saved);
        } else {
            // Revert master formula. The method's declared return type is
            // FormulaOverrideDto, but a master revert has no override row;
            // signal that explicitly rather than silently mapping the wrong
            // type. Callers expecting an override should branch on
            // version.getProjectId() != null before invoking.
            throw new BusinessRuleException(
                    "FORMULA_VERSION_IS_MASTER",
                    "Version " + versionId + " belongs to a master formula, not a project override; "
                            + "use the master-revert API instead.");
        }
    }

    // ---- Seeding helper ----

    @Transactional
    public void seedFormula(String code, String name, FormulaCategory category,
                             String description, String defaultExpression,
                             String inputVariablesJson, FormulaOutputType outputType,
                             Integer scale, String moduleSource, Integer sortOrder) {
        if (formulaMasterRepository.existsByCode(code)) {
            log.debug("Formula {} already exists, skipping seed.", code);
            return;
        }
        FormulaMaster master = new FormulaMaster();
        master.setCode(code);
        master.setName(name);
        master.setCategory(category);
        master.setDescription(description);
        master.setDefaultExpression(defaultExpression);
        master.setInputVariablesJson(inputVariablesJson);
        master.setOutputType(outputType);
        master.setScale(scale != null ? scale : 4);
        master.setRoundingMode(java.math.RoundingMode.HALF_UP);
        master.setZeroDefault("0");
        master.setIsActive(true);
        master.setIsEditable(true);
        master.setSortOrder(sortOrder);
        master.setModuleSource(moduleSource);
        formulaMasterRepository.save(master);
        log.info("Seeded formula master: {} ({})", code, name);
    }

    // ---- Internal helpers ----

    private void saveVersion(FormulaMaster master, FormulaOverride override) {
        saveVersion(master, override, null);
    }

    private void saveVersion(FormulaMaster master, FormulaOverride override, String changeReason) {
        FormulaVersion version = new FormulaVersion();
        version.setFormulaCode(master.getCode());
        version.setProjectId(override != null ? override.getProjectId() : null);

        long nextVersion = formulaVersionRepository.countByFormulaCodeAndProjectId(
                master.getCode(), override != null ? override.getProjectId() : null) + 1;
        version.setVersionNumber((int) nextVersion);

        version.setExpression(override != null ? override.getOverrideExpression() : master.getDefaultExpression());
        version.setChangeReason(changeReason);
        version.setIsActive(override != null ? override.getIsActive() : master.getIsActive());
        version.setEffectiveFrom(override != null ? override.getEffectiveFrom() : null);
        version.setEffectiveTo(override != null ? override.getEffectiveTo() : null);

        formulaVersionRepository.save(version);
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return LocalDate.parse(raw);
    }

    private FormulaMaster mapToEntity(CreateFormulaRequest request) {
        FormulaMaster master = new FormulaMaster();
        master.setCode(request.getCode());
        master.setName(request.getName());
        master.setCategory(request.getCategory());
        master.setDescription(request.getDescription());
        master.setDefaultExpression(request.getDefaultExpression());
        master.setInputVariablesJson(request.getInputVariablesJson());
        master.setOutputType(request.getOutputType());
        master.setScale(request.getScale() != null ? request.getScale() : 4);
        master.setRoundingMode(request.getRoundingMode() != null ? request.getRoundingMode() : java.math.RoundingMode.HALF_UP);
        master.setZeroDefault(request.getZeroDefault() != null ? request.getZeroDefault() : "0");
        master.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);
        master.setIsEditable(request.getIsEditable() != null ? request.getIsEditable() : true);
        master.setSortOrder(request.getSortOrder());
        master.setModuleSource(request.getModuleSource());
        return master;
    }

    private FormulaDto mapToDto(FormulaMaster master) {
        return FormulaDto.builder()
                .id(master.getId())
                .code(master.getCode())
                .name(master.getName())
                .category(master.getCategory())
                .description(master.getDescription())
                .defaultExpression(master.getDefaultExpression())
                .inputVariablesJson(master.getInputVariablesJson())
                .outputType(master.getOutputType())
                .scale(master.getScale())
                .roundingMode(master.getRoundingMode())
                .zeroDefault(master.getZeroDefault())
                .isActive(master.getIsActive())
                .isEditable(master.getIsEditable())
                .sortOrder(master.getSortOrder())
                .moduleSource(master.getModuleSource())
                .formulaVersion(master.getFormulaVersion())
                .build();
    }

    private FormulaOverrideDto mapToDto(FormulaOverride override) {
        return FormulaOverrideDto.builder()
                .id(override.getId())
                .formulaCode(override.getFormulaCode())
                .projectId(override.getProjectId())
                .overrideExpression(override.getOverrideExpression())
                .isActive(override.getIsActive())
                .effectiveFrom(override.getEffectiveFrom() != null ? override.getEffectiveFrom().toString() : null)
                .effectiveTo(override.getEffectiveTo() != null ? override.getEffectiveTo().toString() : null)
                .overrideReason(override.getOverrideReason())
                .overrideVersion(override.getOverrideVersion())
                .build();
    }
}
