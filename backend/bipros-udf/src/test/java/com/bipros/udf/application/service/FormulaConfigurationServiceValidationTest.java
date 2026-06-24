package com.bipros.udf.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.udf.application.dto.CreateFormulaRequest;
import com.bipros.udf.domain.engine.FormulaAstCache;
import com.bipros.udf.domain.model.FormulaCategory;
import com.bipros.udf.domain.model.FormulaMaster;
import com.bipros.udf.domain.model.FormulaOutputType;
import com.bipros.udf.domain.repository.FormulaMasterRepository;
import com.bipros.udf.domain.repository.FormulaOverrideRepository;
import com.bipros.udf.domain.repository.FormulaVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DisplayName("FormulaConfigurationService — save-time validation")
@ExtendWith(MockitoExtension.class)
class FormulaConfigurationServiceValidationTest {

    @Mock
    private FormulaMasterRepository formulaMasterRepository;

    @Mock
    private FormulaOverrideRepository formulaOverrideRepository;

    @Mock
    private FormulaVersionRepository formulaVersionRepository;

    private FormulaAstCache formulaAstCache;
    private FormulaConfigurationService service;

    @BeforeEach
    void setUp() {
        formulaAstCache = new FormulaAstCache();
        service = new FormulaConfigurationService(
                formulaMasterRepository,
                formulaOverrideRepository,
                formulaVersionRepository,
                formulaAstCache);
    }

    @Test
    @DisplayName("rejects formula with syntax error")
    void rejectsSyntaxError() {
        when(formulaMasterRepository.existsByCode("BAD_FORMULA")).thenReturn(false);

        CreateFormulaRequest request = buildRequest("BAD_FORMULA", "IF(1, 2");

        assertThatThrownBy(() -> service.createMasterFormula(request))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getRuleCode())
                .isEqualTo("FORMULA_SYNTAX_ERROR");
    }

    @Test
    @DisplayName("rejects formula with empty expression")
    void rejectsEmptyExpression() {
        when(formulaMasterRepository.existsByCode("EMPTY_FORMULA")).thenReturn(false);

        CreateFormulaRequest request = buildRequest("EMPTY_FORMULA", "");

        assertThatThrownBy(() -> service.createMasterFormula(request))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getRuleCode())
                .isEqualTo("FORMULA_EXPRESSION_EMPTY");
    }

    @Test
    @DisplayName("accepts valid complex formula")
    void acceptsValidFormula() {
        when(formulaMasterRepository.existsByCode("GOOD_FORMULA")).thenReturn(false);
        when(formulaMasterRepository.save(any(FormulaMaster.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateFormulaRequest request = buildRequest("GOOD_FORMULA", "IF($AC = 0, 0, $EV / $AC)");

        // Should not throw during validation
        assertThatCode(() -> service.createMasterFormula(request))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("update preserves is_active/is_editable/rounding_mode/zero_default when the edit form omits them")
    void updatePreservesFieldsNotInEditForm() {
        UUID id = UUID.randomUUID();
        FormulaMaster existing = new FormulaMaster();
        existing.setCode("RPT_VAC");
        existing.setName("Report VAC");
        existing.setCategory(FormulaCategory.REPORTING);
        existing.setDefaultExpression("$BAC - $EAC");
        existing.setOutputType(FormulaOutputType.CURRENCY);
        existing.setScale(2);
        existing.setRoundingMode(RoundingMode.HALF_UP);
        existing.setZeroDefault("0");
        existing.setIsActive(true);
        existing.setIsEditable(true);

        when(formulaMasterRepository.findById(id)).thenReturn(Optional.of(existing));
        when(formulaMasterRepository.save(any(FormulaMaster.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // The admin edit form changes only the description; it has no inputs for is_active,
        // is_editable, rounding_mode or zero_default, so they arrive null in the request.
        CreateFormulaRequest request = CreateFormulaRequest.builder()
                .code("RPT_VAC")
                .name("Report VAC")
                .category(FormulaCategory.REPORTING)
                .description("VAC used in reports — edited")
                .defaultExpression("$BAC - $EAC")
                .outputType(FormulaOutputType.CURRENCY)
                .scale(2)
                .build();

        service.updateMasterFormula(id, request);

        assertThat(existing.getIsActive()).isTrue();
        assertThat(existing.getIsEditable()).isTrue();
        assertThat(existing.getRoundingMode()).isEqualTo(RoundingMode.HALF_UP);
        assertThat(existing.getZeroDefault()).isEqualTo("0");
        assertThat(existing.getDescription()).isEqualTo("VAC used in reports — edited");
    }

    private CreateFormulaRequest buildRequest(String code, String expression) {
        return CreateFormulaRequest.builder()
                .code(code)
                .name("Test Formula")
                .category(FormulaCategory.EVM)
                .defaultExpression(expression)
                .outputType(FormulaOutputType.NUMBER)
                .scale(4)
                .roundingMode(RoundingMode.HALF_UP)
                .build();
    }
}
