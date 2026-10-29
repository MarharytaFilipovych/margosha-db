package com.margosha.ycsb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Properties;
import java.util.Vector;

import com.margosha.api.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import site.ycsb.DB;
import site.ycsb.DBException;
import site.ycsb.Status;

class MargoshaDbClientTest {

    @Test
    void apiErrorsMapToYcsbStatuses() {
        assertThat(MargoshaDbClient.statusFor(new ApiException(404, null, null))).isEqualTo(Status.NOT_FOUND);
        assertThat(MargoshaDbClient.statusFor(new ApiException(400, null, null))).isEqualTo(Status.BAD_REQUEST);
        assertThat(MargoshaDbClient.statusFor(new ApiException(503, null, null))).isEqualTo(Status.SERVICE_UNAVAILABLE);
        assertThat(MargoshaDbClient.statusFor(new ApiException(409, null, null))).isEqualTo(Status.ERROR);
        assertThat(MargoshaDbClient.statusFor(new ApiException(500, null, null))).isEqualTo(Status.ERROR);
    }

    @Test
    void noAnswerFromTheCoordinatorIsServiceUnavailable() {
        assertThat(MargoshaDbClient.statusFor(new ResourceAccessException("timed out"))).isEqualTo(Status.SERVICE_UNAVAILABLE);
        assertThat(MargoshaDbClient.statusFor(new IllegalStateException("bug"))).isEqualTo(Status.ERROR);
    }

    @Test
    void scanIsNotImplemented() throws DBException {
        DB db = client(new Properties());

        assertThat(db.scan("usertable", "user1", 10, null, new Vector<>())).isEqualTo(Status.NOT_IMPLEMENTED);
    }

    @Test
    void unreachableCoordinatorIsServiceUnavailable() throws DBException {
        Properties properties = new Properties();
        properties.setProperty(MargoshaDbClient.URL_PROPERTY, "http://127.0.0.1:9");
        properties.setProperty(MargoshaDbClient.TIMEOUT_PROPERTY, "1000");
        DB db = client(properties);

        assertThat(db.read("usertable", "user1", null, new HashMap<>())).isEqualTo(Status.SERVICE_UNAVAILABLE);
    }

    @Test
    void timeoutMustBeANumber() {
        Properties properties = new Properties();
        properties.setProperty(MargoshaDbClient.TIMEOUT_PROPERTY, "10s");

        assertThatThrownBy(() -> client(properties)).isInstanceOf(DBException.class);
    }

    private static DB client(Properties properties) throws DBException {
        DB db = new MargoshaDbClient();
        db.setProperties(properties);
        db.init();
        return db;
    }
}
