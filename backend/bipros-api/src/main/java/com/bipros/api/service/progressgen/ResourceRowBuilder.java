package com.bipros.api.service.progressgen;

import com.bipros.project.application.dto.DprEquipmentRow;
import com.bipros.project.application.dto.DprManpowerRow;
import com.bipros.project.application.dto.DprMaterialRow;
import com.bipros.resource.domain.model.ResourceAssignment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds DPR child rows from an activity's resource plan, scaled by a fraction. nos/quantity here
 * are the activity TOTAL; the orchestrator divides them across the DPR slots. Rates/line cost left
 * null (the server snapshots them on create).
 *
 * DprManpowerRow  (17): id, resourceAssignmentId, resourceId, trade, category, shift,
 *                       nos, workingHours, otHours, idleHours, unitRate, unitRateBasis,
 *                       lineCost, contractorName, remarks, manpowerRoleRateId, roleId
 * DprEquipmentRow (20): id, resourceAssignmentId, resourceId, equipmentType, fleetNo,
 *                       ownership, shift, nos, workingHours, idleHours, breakdownHours,
 *                       fuelLitres, unitRate, unitRateBasis, lineCost, operatorName,
 *                       availabilityStatus, remarks, equipmentRoleVariantId, roleId
 * DprMaterialRow  (15): id, resourceAssignmentId, materialId, resourceId, materialName,
 *                       quantity, unit, source, batchNo, vendorName, unitRate, lineCost,
 *                       remarks, materialRoleVariantId, roleId
 */
@Component
public class ResourceRowBuilder {

  public record Rows(
      List<DprManpowerRow> manpower,
      List<DprEquipmentRow> equipment,
      List<DprMaterialRow> materials) {}

  public Rows build(List<ResourceAssignment> plan, double fraction, int workingHoursPerDay) {
    List<DprManpowerRow> mp = new ArrayList<>();
    List<DprEquipmentRow> eq = new ArrayList<>();
    List<DprMaterialRow> mat = new ArrayList<>();
    BigDecimal hours = BigDecimal.valueOf(workingHoursPerDay);
    for (ResourceAssignment a : plan) {
      if (a.getManpowerRoleRateId() != null) {
        int nos = scaleCount(a.getHeadcount(), fraction);
        if (nos <= 0) continue;
        mp.add(new DprManpowerRow(
            null, null, null,
            "Auto",   // trade (@NotBlank)
            null,     // category
            null,     // shift
            nos, hours,
            BigDecimal.ZERO, BigDecimal.ZERO,  // otHours, idleHours
            null, null, null, null,             // unitRate, unitRateBasis, lineCost, contractorName
            "auto-generated",
            a.getManpowerRoleRateId(), a.getRoleId()));
      } else if (a.getEquipmentRoleVariantId() != null) {
        int nos = scaleCount(a.getHeadcount(), fraction);
        if (nos <= 0) continue;
        eq.add(new DprEquipmentRow(
            null, null, null,
            "Auto",   // equipmentType (@NotBlank)
            null,     // fleetNo
            null,     // ownership
            null,     // shift
            nos, hours,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,  // idleHours, breakdownHours, fuelLitres
            null, null, null, null, null,                        // unitRate, unitRateBasis, lineCost, operatorName, availabilityStatus
            "auto-generated",
            a.getEquipmentRoleVariantId(), a.getRoleId()));
      } else if (a.getMaterialRoleVariantId() != null) {
        BigDecimal qty = scaleQty(a.getQuantity(), fraction);
        if (qty.signum() <= 0) continue;
        mat.add(new DprMaterialRow(
            null, null, null, null,
            "Auto",  // materialName (@NotBlank)
            qty, a.getUnit(),
            null, null, null,  // source, batchNo, vendorName
            null, null,        // unitRate, lineCost
            "auto-generated",
            a.getMaterialRoleVariantId(), a.getRoleId()));
      }
    }
    return new Rows(mp, eq, mat);
  }

  private int scaleCount(Integer headcount, double fraction) {
    if (headcount == null || headcount <= 0) return 0;
    int n = (int) Math.round(headcount * fraction);
    return Math.max(n, 1);
  }

  private BigDecimal scaleQty(BigDecimal qty, double fraction) {
    if (qty == null) return BigDecimal.ZERO;
    return qty.multiply(BigDecimal.valueOf(fraction)).setScale(1, RoundingMode.HALF_UP);
  }
}
