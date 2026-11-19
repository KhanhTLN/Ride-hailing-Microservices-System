package com.threecats.driverservice.driver.service;

import com.threecats.common.exception.BaseException;

final class LocationVersionConflict extends BaseException {
    LocationVersionConflict(String message) {
        super(409, "VERSION_CONFLICT", message);
    }
}
