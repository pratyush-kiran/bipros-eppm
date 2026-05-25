package com.bipros.bootstrap.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The structured intermediate that Stage0Parse writes and downstream stages
 * read. Fields are intentionally plain so Jackson can round-trip the dataset
 * to {@code parsed-dataset.json} for human inspection between runs.
 *
 * <p>Built bottom-up from the DPR data: the DPR rows are the source of truth,
 * and the rest is derived from them.
 */
public class ParsedDataset {

    public ProjectInfo project = new ProjectInfo();
    public List<ManpowerVariant> manpowerVariants = new ArrayList<>();
    public List<EquipmentVariant> equipmentVariants = new ArrayList<>();
    public List<MaterialVariant> materialVariants = new ArrayList<>();
    public List<WorkActivityInfo> workActivities = new ArrayList<>();
    public List<WbsChapter> wbsChapters = new ArrayList<>();
    public List<ActivityInfo> activities = new ArrayList<>();
    public List<BoqInfo> boqItems = new ArrayList<>();
    public List<DprRecord> dprRecords = new ArrayList<>();

    /** Issues found during parsing that block downstream stages. */
    public List<String> validationErrors = new ArrayList<>();
    /** Issues found that the user should review but do not block. */
    public List<String> warnings = new ArrayList<>();

    public static class ProjectInfo {
        public String code;
        public String name;
        public String description;
        public LocalDate plannedStart;
        public LocalDate plannedFinish;
        public String currency;
        public String calendarCode;
        public String fromLocation;
        public String toLocation;
        public Long fromChainageM;
        public Long toChainageM;
        public String morthCode;
        public String category;
    }

    /** One row in resource.manpower_role_rates. Derived from distinct trades in DPRs. */
    public static class ManpowerVariant {
        public String roleCode;
        public String roleName;
        /** Master codes; created in Stage2 if absent. */
        public String categoryCode;
        public String gradeCode;
        public String unit;
        public BigDecimal rate;
    }

    /** One row in resource.equipment_role_variants. */
    public static class EquipmentVariant {
        public String roleCode;
        public String roleName;
        public String make;
        public String model;
        public String unit;
        public BigDecimal rate;
        public BigDecimal standardOutputPerDay;
    }

    /** One row in resource.material_role_variants. */
    public static class MaterialVariant {
        public String roleCode;
        public String roleName;
        public String specGrade;
        public String unit;
        public BigDecimal rate;
    }

    public static class WorkActivityInfo {
        public String code;
        public String name;
        public String defaultUnit;
        public String discipline;
        /** SERIES (default) / PARALLEL / SUBSTITUTE */
        public String normCombination;
        /** Empirical manpower productivity from DPRs (output per man-day, unscoped). */
        public BigDecimal outputPerManPerDay;
        /** Empirical equipment productivity from DPRs (output per equipment-hour). */
        public BigDecimal outputPerHour;
    }

    public static class WbsChapter {
        /** Stable code (e.g. "PREP", "EARTH"). */
        public String code;
        public String name;
        public int sortOrder;
    }

    public static class ActivityInfo {
        public String code;
        public String name;
        public String wbsChapterCode;
        public String workActivityCode;
        public LocalDate plannedStart;
        public LocalDate plannedFinish;
        public Long chainageFromM;
        public Long chainageToM;
        /** Supervisor names found in DPRs for this activity. */
        public List<String> supervisorNames = new ArrayList<>();
    }

    public static class BoqInfo {
        public String wbsChapterCode;
        public String itemNo;
        public String description;
        public String unit;
        public BigDecimal boqQty;
        public BigDecimal boqRate;
        public BigDecimal budgetedRate;
        public String chapter;
    }

    public static class DprRecord {
        public LocalDate date;
        public String activityCode;
        public String supervisorName;
        public String boqItemNo;
        public BigDecimal workDoneQty;
        public String unit;
        public Long chainageFromM;
        public Long chainageToM;
        public String shift;
        public String weather;
        public String remarks;
        public List<DprManpowerRow> manpower = new ArrayList<>();
        public List<DprEquipmentRow> equipment = new ArrayList<>();
        public List<DprMaterialRow> materials = new ArrayList<>();
    }

    public static class DprManpowerRow {
        public String roleCode;
        public String categoryCode;
        public String gradeCode;
        public Integer nos;
        public BigDecimal workingHours;
        public BigDecimal otHours;
        public BigDecimal idleHours;
        public BigDecimal unitRate;
        public String contractorName;
    }

    public static class DprEquipmentRow {
        public String roleCode;
        public String make;
        public String model;
        public Integer nos;
        public BigDecimal workingHours;
        public BigDecimal idleHours;
        public BigDecimal breakdownHours;
        public BigDecimal fuelLitres;
        public BigDecimal unitRate;
    }

    public static class DprMaterialRow {
        public String roleCode;
        public String specGrade;
        public BigDecimal quantity;
        public String unit;
        public BigDecimal unitRate;
        public String vendorName;
    }
}
