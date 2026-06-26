package com.bipros.api.service;

import com.bipros.admin.domain.model.GlobalSetting;
import com.bipros.admin.domain.repository.GlobalSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DprSlaConfig")
class DprSlaConfigTest {

  @Mock
  private GlobalSettingRepository globalSettingRepository;

  private DprSlaConfig dprSlaConfig;

  @BeforeEach
  void setUp() {
    dprSlaConfig = new DprSlaConfig(globalSettingRepository);
  }

  private GlobalSetting settingWith(String value) {
    GlobalSetting s = new GlobalSetting();
    s.setSettingKey(DprSlaConfig.KEY);
    s.setSettingValue(value);
    return s;
  }

  @Test
  @DisplayName("valid value '48' → 48")
  void validValue_returns48() {
    when(globalSettingRepository.findBySettingKey(DprSlaConfig.KEY))
        .thenReturn(Optional.of(settingWith("48")));
    assertThat(dprSlaConfig.slaHours()).isEqualTo(48);
  }

  @Test
  @DisplayName("missing setting → default 24")
  void missingKey_returnsDefault() {
    when(globalSettingRepository.findBySettingKey(DprSlaConfig.KEY))
        .thenReturn(Optional.empty());
    assertThat(dprSlaConfig.slaHours()).isEqualTo(24);
  }

  @Test
  @DisplayName("non-numeric value 'abc' → default 24")
  void nonNumericValue_returnsDefault() {
    when(globalSettingRepository.findBySettingKey(DprSlaConfig.KEY))
        .thenReturn(Optional.of(settingWith("abc")));
    assertThat(dprSlaConfig.slaHours()).isEqualTo(24);
  }

  @Test
  @DisplayName("negative value '-5' → default 24")
  void negativeValue_returnsDefault() {
    when(globalSettingRepository.findBySettingKey(DprSlaConfig.KEY))
        .thenReturn(Optional.of(settingWith("-5")));
    assertThat(dprSlaConfig.slaHours()).isEqualTo(24);
  }

  @Test
  @DisplayName("zero value '0' → 0 (immediate escalation allowed)")
  void zeroValue_returnsZero() {
    when(globalSettingRepository.findBySettingKey(DprSlaConfig.KEY))
        .thenReturn(Optional.of(settingWith("0")));
    assertThat(dprSlaConfig.slaHours()).isEqualTo(0);
  }
}
