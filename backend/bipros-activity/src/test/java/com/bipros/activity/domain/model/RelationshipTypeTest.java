package com.bipros.activity.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("RelationshipType code() maps to CPM engine short codes")
class RelationshipTypeTest {

  @Test
  @DisplayName("FINISH_TO_START.code() == \"FS\"")
  void finishToStartCode() {
    assertEquals("FS", RelationshipType.FINISH_TO_START.code());
  }

  @Test
  @DisplayName("FINISH_TO_FINISH.code() == \"FF\"")
  void finishToFinishCode() {
    assertEquals("FF", RelationshipType.FINISH_TO_FINISH.code());
  }

  @Test
  @DisplayName("START_TO_START.code() == \"SS\"")
  void startToStartCode() {
    assertEquals("SS", RelationshipType.START_TO_START.code());
  }

  @Test
  @DisplayName("START_TO_FINISH.code() == \"SF\"")
  void startToFinishCode() {
    assertEquals("SF", RelationshipType.START_TO_FINISH.code());
  }
}
