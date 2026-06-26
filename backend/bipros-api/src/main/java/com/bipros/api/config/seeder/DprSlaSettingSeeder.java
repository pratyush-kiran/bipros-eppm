package com.bipros.api.config.seeder;

import com.bipros.admin.domain.model.GlobalSetting;
import com.bipros.admin.domain.repository.GlobalSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Seeds the dpr_sla_hours global setting (default 24) once, in EVERY profile, so it appears in Admin → Settings. */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(1000)
public class DprSlaSettingSeeder implements CommandLineRunner {

  private final GlobalSettingRepository globalSettingRepository;

  @Override
  @Transactional
  public void run(String... args) {
    if (globalSettingRepository.findBySettingKey("dpr_sla_hours").isPresent()) return;
    GlobalSetting s = new GlobalSetting();
    s.setSettingKey("dpr_sla_hours");
    s.setSettingValue("24");
    s.setDescription("Hours a submitted DPR may sit unapproved before an SLA reminder is sent to the approver and their manager.");
    s.setCategory("OPERATIONS");
    globalSettingRepository.save(s);
    log.info("[DprSlaSettingSeeder] seeded dpr_sla_hours=24");
  }
}
