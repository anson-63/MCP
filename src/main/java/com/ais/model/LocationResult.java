package com.ais.model;

import java.time.LocalDate;
import java.util.Objects;

public class LocationResult {

    private String  locationCode;
    private String  locationName;
    private String  address;
    private boolean isMonument;
    private String  historicGrade;   // null = not in historic table
    private String  department;
    private String  psm;
    private LocalDate createdDate;

    // ── static factories ──────────────────────────────────────────────────

    public static LocationResult countResult(int count) {
        LocationResult r = new LocationResult();
        r.locationName = "COUNT: " + count;
        return r;
    }

    // ── merge enrichment from a secondary result ──────────────────────────

    public void mergeFrom(LocationResult other) {
        if (other.historicGrade != null) this.historicGrade = other.historicGrade;
        if (other.department    != null) this.department    = other.department;
        if (other.psm           != null) this.psm           = other.psm;
    }

    // ── equals / hashCode on locationCode for dedup ───────────────────────

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LocationResult)) return false;
        return Objects.equals(locationCode, ((LocationResult) o).locationCode);
    }

    @Override public int hashCode() {
        return Objects.hashCode(locationCode);
    }

    // ── getters / setters ────────────────────────────────────────────────

    public String    getLocationCode()  { return locationCode; }
    public String    getLocationName()  { return locationName; }
    public String    getAddress()       { return address; }
    public boolean   isMonument()       { return isMonument; }
    public String    getHistoricGrade() { return historicGrade; }
    public String    getDepartment()    { return department; }
    public String    getPsm()           { return psm; }
    public LocalDate getCreatedDate()   { return createdDate; }

    public void setLocationCode(String v)  { this.locationCode = v; }
    public void setLocationName(String v)  { this.locationName = v; }
    public void setAddress(String v)       { this.address = v; }
    public void setMonument(boolean v)     { this.isMonument = v; }
    public void setHistoricGrade(String v) { this.historicGrade = v; }
    public void setDepartment(String v)    { this.department = v; }
    public void setPsm(String v)           { this.psm = v; }
    public void setCreatedDate(LocalDate v){ this.createdDate = v; }
}