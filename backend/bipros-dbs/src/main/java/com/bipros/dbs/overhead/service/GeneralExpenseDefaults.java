package com.bipros.dbs.overhead.service;

import com.bipros.dbs.overhead.domain.model.GeneralExpenseFormulaType;
import com.bipros.dbs.overhead.domain.model.GeneralExpenseUnit;

import java.math.BigDecimal;
import java.util.List;

/**
 * Canonical 20-line Section G template lifted from the PRE sheet of the
 * supplied DBS Excel ("3. Supervisor-Engineer-CM-PM DBS (2).xlsx"). Seeded
 * verbatim into every newly created project with {@code planQty=0}; PM edits
 * quantities/amounts to budget the project.
 *
 * <p>Insurance and Bank Charges are flagged {@code PCT_CONTRACT_VALUE} so the
 * UI can surface the formula hint (0.015 % and 0.01 % of contract value
 * respectively). The actual {@code planAmount} still flows from the PM's
 * explicit input — the formula is informational, not enforced.
 */
public final class GeneralExpenseDefaults {

    public record Item(
        String description,
        GeneralExpenseUnit unit,
        GeneralExpenseFormulaType formulaType,
        BigDecimal formulaPct
    ) {}

    public static final List<Item> ITEMS = List.of(
        item("Electricity Charges",              GeneralExpenseUnit.MONTH),
        item("Water & Sewage Charges",           GeneralExpenseUnit.MONTH),
        item("Rent Land & Office, Accommodation",GeneralExpenseUnit.MONTH),
        item("Staff Welfare",                    GeneralExpenseUnit.MONTH),
        item("Safety Expenses",                  GeneralExpenseUnit.MONTH),
        item("Medical Expenses",                 GeneralExpenseUnit.MONTH),
        item("Printing & Stationary",            GeneralExpenseUnit.MONTH),
        item("Communication Expenses",           GeneralExpenseUnit.MONTH),
        item("Business Promotion",               GeneralExpenseUnit.MONTH),
        item("Travel & Conveyance",              GeneralExpenseUnit.MONTH),
        item("Legal & Professional Charges",     GeneralExpenseUnit.MONTH),
        item("Consultant Overtime",              GeneralExpenseUnit.MONTH),
        item("Repairs & Maintenance",            GeneralExpenseUnit.MONTH),
        item("Lab Testing Charges",              GeneralExpenseUnit.MONTH),
        item("Other Miscellaneous Expenses",     GeneralExpenseUnit.MONTH),
        item("Depreciation — Equipment & Furniture", GeneralExpenseUnit.LS),
        formula("Insurance Charges (0.015% of CV)", new BigDecimal("0.000150")),
        formula("Bank Charges (0.01% of CV)",       new BigDecimal("0.000100")),
        item("Contingency",                      GeneralExpenseUnit.MONTH),
        item("Accommodation Charges",            GeneralExpenseUnit.MONTH)
    );

    private static Item item(String desc, GeneralExpenseUnit unit) {
        return new Item(desc, unit, GeneralExpenseFormulaType.NONE, null);
    }

    private static Item formula(String desc, BigDecimal pct) {
        return new Item(desc, GeneralExpenseUnit.MONTH, GeneralExpenseFormulaType.PCT_CONTRACT_VALUE, pct);
    }

    private GeneralExpenseDefaults() {}
}
