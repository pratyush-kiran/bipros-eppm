package com.bipros.api.service;

import com.bipros.admin.domain.model.GlobalSetting;
import com.bipros.admin.domain.repository.GlobalSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Reads the configurable DPR SLA window (hours). Defaults to 24 when the setting is missing/invalid. */
@Service
@RequiredArgsConstructor
public class DprSlaConfig {

  static final String KEY = "dpr_sla_hours";
  static final int DEFAULT_HOURS = 24;

  private final GlobalSettingRepository globalSettingRepository;

  public int slaHours() {
    return globalSettingRepository.findBySettingKey(KEY)
        .map(GlobalSetting::getSettingValue)
        .map(this::parseOrDefault)
        .orElse(DEFAULT_HOURS);
  }

  private int parseOrDefault(String v) {
    try {
      int h = Integer.parseInt(v.trim());
      return h < 0 ? DEFAULT_HOURS : h;   // 0 is allowed (immediate escalation — useful for testing)
    } catch (NumberFormatException | NullPointerException e) {
      return DEFAULT_HOURS;
    }
  }
}
