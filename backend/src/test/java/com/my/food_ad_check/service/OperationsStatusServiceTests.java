package com.my.food_ad_check.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationsStatusServiceTests {
    @Test void databaseFailureReturnsUnknownMetricsWithoutExceptionDetails() {
        var db=mock(JdbcTemplate.class);
        when(db.queryForObject("SELECT 1",Integer.class))
                .thenThrow(new DataAccessResourceFailureException("private-password private-host"));
        var result=new OperationsStatusService(db,true,false,false).snapshot();
        assertEquals("UNAVAILABLE",result.status());
        assertEquals("DOWN",result.database().status());
        assertEquals("DATABASE_UNAVAILABLE",result.errorCode());
        assertNull(result.inspections());
        assertNull(result.productSync());
        assertNull(result.email());
        assertFalse(result.toString().contains("private-"));
        assertEquals("NOT_CHECKED",result.externalChecks().judge());
    }

    @Test void incompleteSchemaDoesNotPretendToBeAHealthyZeroQueue() {
        var db=mock(JdbcTemplate.class);
        when(db.queryForObject("SELECT 1",Integer.class)).thenReturn(1);
        when(db.queryForObject("SELECT current_setting('TimeZone')",String.class))
                .thenThrow(new DataAccessResourceFailureException("private connection detail"));
        var result=new OperationsStatusService(db,true,false,false).snapshot();
        assertEquals("UP",result.database().status());
        assertEquals("UNAVAILABLE",result.status());
        assertEquals("STATUS_QUERY_FAILED",result.errorCode());
        assertNull(result.inspections());
        assertNull(result.email());
        assertFalse(result.toString().contains("private"));
    }
}
