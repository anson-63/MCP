package com.ais.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class LocationInfo {
    private String LOC_CD;
    public String getLOC_CD() {return LOC_CD;}
    public void setLOC_CD(String LOC_CD) {this.LOC_CD = LOC_CD;}
    
    private String LOC_NAME;
    public String getLOC_NAME() {return LOC_NAME;}
    public void setLOC_NAME(String LOC_NAME) {this.LOC_NAME = LOC_NAME;}

    private String ZONE;
    public String getZONE() {return ZONE;}
    public void setZONE(String ZONE) {this.ZONE = ZONE;}
    
    private String STATUS;
    public String getSTATUS() {return STATUS;}
    public void setSTATUS(String STATUS) {this.STATUS = STATUS;}

    private String DESCRIPTION;
    public String getDESCRIPTION() {return DESCRIPTION;}
    public void setDESCRIPTION(String DESCRIPTION) {this.DESCRIPTION = DESCRIPTION;}

    // Add more fields matching your DB columns
    private String error; // for error responses
    public String getError() {return error;}
    public void setError(String error) {this.error = error;}
}