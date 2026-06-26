package com.ais.service;

public enum IntentRole {
    PRIMARY,           // produces a result set
    SECONDARY,         // enriches/filters result set from previous step
    ACTION,            // fetches detail for a known code directly
    ACTION_ON_RESULT,  // picks code from result set then fetches detail
    MODIFIER           // handled at execution level, not a tool step
}