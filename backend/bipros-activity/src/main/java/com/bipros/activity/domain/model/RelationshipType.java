package com.bipros.activity.domain.model;

public enum RelationshipType {
  FINISH_TO_START("FS"),
  FINISH_TO_FINISH("FF"),
  START_TO_START("SS"),
  START_TO_FINISH("SF");

  private final String code;

  RelationshipType(String code) {
    this.code = code;
  }

  /** Short PDM code used by the CPM engine ("FS"/"FF"/"SS"/"SF"). */
  public String code() {
    return code;
  }
}
