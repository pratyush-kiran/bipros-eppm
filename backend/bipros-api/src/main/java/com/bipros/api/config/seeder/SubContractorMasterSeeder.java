package com.bipros.api.config.seeder;

import com.bipros.resource.domain.model.SubContractorWorkActivityMapping;
import com.bipros.resource.domain.model.SubContractorWorkType;
import com.bipros.resource.domain.model.master.SubContractorMaster;
import com.bipros.resource.domain.repository.SubContractorMasterRepository;
import com.bipros.resource.domain.repository.SubContractorWorkActivityMappingRepository;
import com.bipros.resource.domain.repository.SubContractorWorkTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Idempotently seeds a small directory of demo sub-contractor organisations with
 * work-type rate mappings, so the Activity Resource Demand panel has data to
 * pick from out-of-the-box. Skips if {@code sub_contractor_master} is non-empty.
 *
 * <p>Runs as an {@code ApplicationReadyEvent} listener so all
 * {@code CommandLineRunner}-based project seeders have already run.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(200)
public class SubContractorMasterSeeder {

  private final SubContractorMasterRepository masterRepo;
  private final SubContractorWorkActivityMappingRepository mappingRepo;
  private final SubContractorWorkTypeRepository workTypeRepo;

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void seed() {
    if (masterRepo.count() > 0) {
      log.info("[SubContractorMasterSeeder] sub_contractor_master not empty — skipping");
      return;
    }

    List<SubContractorWorkType> workTypes = seedWorkTypes();
    if (workTypes.isEmpty()) {
      log.info("[SubContractorMasterSeeder] no work types seeded — skipping");
      return;
    }

    List<ContractorSpec> specs = specs();
    int contractorCount = 0;
    int mappingCount = 0;
    for (ContractorSpec spec : specs) {
      SubContractorMaster saved = masterRepo.save(SubContractorMaster.builder()
          .code(spec.code())
          .name(spec.name())
          .location(spec.location())
          .primaryContactName(spec.contactName())
          .primaryContactNumber(spec.contactNumber())
          .remarks(spec.remarks())
          .active(true)
          .build());
      contractorCount++;

      List<SubContractorWorkActivityMapping> mappings = new ArrayList<>();
      for (MappingRule rule : spec.rules()) {
        for (SubContractorWorkType wt : workTypes) {
          if (!matchesAny(wt, rule.keywords())) continue;
          if (mappings.stream().anyMatch(m -> m.getScWorkTypeId().equals(wt.getId()))) continue;
          mappings.add(SubContractorWorkActivityMapping.builder()
              .subContractorMasterId(saved.getId())
              .scWorkTypeId(wt.getId())
              .workTypeName(wt.getName())
              .unit(wt.getDefaultUnit())
              .ratePerUnit(rule.ratePerUnit())
              .outputPerDay(rule.outputPerDay())
              .build());
          if (mappings.size() >= 6) break;
        }
        if (mappings.size() >= 6) break;
      }
      if (!mappings.isEmpty()) {
        mappingRepo.saveAll(mappings);
        mappingCount += mappings.size();
      }
    }
    log.info("[SubContractorMasterSeeder] seeded {} sub-contractors with {} work-type mappings",
        contractorCount, mappingCount);
  }

  private List<SubContractorWorkType> seedWorkTypes() {
    List<String> names = List.of(
        "Earthwork", "Excavation", "Embankment", "Filling",
        "Granular Sub-Base", "Wet Mix Macadam", "Dense Bituminous Macadam",
        "Bituminous Concrete", "Pavement Layer",
        "Concrete Work", "PCC", "RCC", "Pour",
        "Steel Work", "Reinforcement", "Bar Bending", "Rebar",
        "Formwork", "Shuttering", "Scaffolding",
        "Masonry", "Brickwork", "Plastering", "Blockwork",
        "Electrical Installation", "Cable Laying", "Conduit", "Wiring", "Panel",
        "Plumbing", "Pipe Laying", "Drainage", "Sanitary", "Water Supply",
        "Painting", "Finishing", "Primer",
        "Welding", "Fabrication", "Structural Steel", "Erection");

    List<SubContractorWorkType> result = new ArrayList<>();
    for (String name : names) {
      workTypeRepo.findByNameIgnoreCase(name).ifPresentOrElse(
          result::add,
          () -> result.add(workTypeRepo.save(SubContractorWorkType.builder()
              .name(name)
              .defaultUnit("Cum")
              .active(true)
              .build())));
    }
    return result;
  }

  private static boolean matchesAny(SubContractorWorkType wt, List<String> keywords) {
    String name = wt.getName() == null ? "" : wt.getName().toLowerCase();
    for (String k : keywords) {
      if (name.contains(k.toLowerCase())) return true;
    }
    return false;
  }

  private static List<ContractorSpec> specs() {
    return List.of(
        new ContractorSpec(
            "SC-EARTH-01", "Shree Earthmovers Pvt Ltd", "Pune, Maharashtra",
            "Mahesh Patil", "+91 98220 11122",
            "Earthwork specialist — excavation, embankment, sub-grade",
            List.of(
                new MappingRule(List.of("earthwork", "excavation", "embankment", "filling"),
                    new BigDecimal("285.00"), new BigDecimal("450")))),
        new ContractorSpec(
            "SC-PAVE-01", "Bharat Pavement Co", "Nagpur, Maharashtra",
            "Sunil Deshmukh", "+91 98230 22233",
            "Road pavement layers — GSB, WMM, DBM, BC",
            List.of(
                new MappingRule(List.of("granular sub-base", "wet mix macadam",
                    "dense bituminous macadam", "bituminous concrete", "pavement layer"),
                    new BigDecimal("680.00"), new BigDecimal("180")))),
        new ContractorSpec(
            "SC-CONC-01", "Apex Concrete Solutions", "Hyderabad, Telangana",
            "Ravi Kumar", "+91 99490 33344",
            "Concrete works — PCC, RCC, pours and curing",
            List.of(
                new MappingRule(List.of("concrete work", "pcc", "rcc", "pour"),
                    new BigDecimal("4250.00"), new BigDecimal("85")))),
        new ContractorSpec(
            "SC-STL-01", "Reliable Steel Works", "Raipur, Chhattisgarh",
            "Imran Sheikh", "+91 94250 44455",
            "Steel — cutting, bending, fixing, reinforcement",
            List.of(
                new MappingRule(List.of("steel work", "reinforcement", "bar bending", "rebar"),
                    new BigDecimal("8.50"), new BigDecimal("1800")))),
        new ContractorSpec(
            "SC-FORM-01", "Vinayak Formworks", "Vadodara, Gujarat",
            "Nitin Shah", "+91 99980 55566",
            "Shuttering, scaffolding, formwork erection & dismantling",
            List.of(
                new MappingRule(List.of("formwork", "shuttering", "scaffolding"),
                    new BigDecimal("215.00"), new BigDecimal("260")))),
        new ContractorSpec(
            "SC-CIVIL-01", "Sairam Civil Contractors", "Bhubaneswar, Odisha",
            "Subrat Mohanty", "+91 94370 66677",
            "General civil — masonry, plastering, brickwork",
            List.of(
                new MappingRule(List.of("masonry", "brickwork", "plastering", "blockwork"),
                    new BigDecimal("520.00"), new BigDecimal("75")))),
        new ContractorSpec(
            "SC-ELEC-01", "Sunrise Electricals & Allied Services", "Bengaluru, Karnataka",
            "Anil Reddy", "+91 98860 77788",
            "Electrical installation — conduit, wiring, panels, fittings",
            List.of(
                new MappingRule(List.of("electrical installation", "cable laying", "conduit", "wiring", "panel"),
                    new BigDecimal("145.00"), new BigDecimal("120")))),
        new ContractorSpec(
            "SC-PLB-01", "Skyline Plumbing Services", "Chennai, Tamil Nadu",
            "Karthik Subramaniam", "+91 98400 88899",
            "Plumbing — water supply, drainage, sanitary fixtures",
            List.of(
                new MappingRule(List.of("plumbing", "pipe laying", "drainage", "sanitary", "water supply"),
                    new BigDecimal("210.00"), new BigDecimal("95")))),
        new ContractorSpec(
            "SC-PNT-01", "PrimeFinish Painters & Decorators", "Indore, Madhya Pradesh",
            "Rakesh Verma", "+91 99070 99900",
            "Surface preparation, primer and finish painting",
            List.of(
                new MappingRule(List.of("painting", "finishing", "primer"),
                    new BigDecimal("38.00"), new BigDecimal("220")))),
        new ContractorSpec(
            "SC-WELD-01", "Trinity Welding & Fabrication Works", "Jamshedpur, Jharkhand",
            "Joseph D'Souza", "+91 94310 10101",
            "Structural welding, fabrication, gas cutting",
            List.of(
                new MappingRule(List.of("welding", "fabrication", "structural steel", "erection"),
                    new BigDecimal("95.00"), new BigDecimal("65"))))
    );
  }

  private record ContractorSpec(
      String code,
      String name,
      String location,
      String contactName,
      String contactNumber,
      String remarks,
      List<MappingRule> rules) {}

  private record MappingRule(
      List<String> keywords,
      BigDecimal ratePerUnit,
      BigDecimal outputPerDay) {}
}
