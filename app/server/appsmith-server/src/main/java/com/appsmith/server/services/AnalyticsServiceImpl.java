package com.appsmith.server.services;

import com.appsmith.server.services.ce.AnalyticsServiceCEImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Fork: inert, see {@link AnalyticsServiceCEImpl}.
 */
@Slf4j
@Service
public class AnalyticsServiceImpl extends AnalyticsServiceCEImpl implements AnalyticsService {}
