package com.urlshortener.write.config;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaInitializerTest {

    @Mock
    private CqlSession cqlSession;

    @Mock
    private ResultSet resultSet;

    @Test
    void initSchemaCreatesSingleNodeKeyspaceAndQualifiedTablesByDefault() throws IOException {
        when(cqlSession.execute(anyString())).thenReturn(resultSet);

        WriteServiceProperties properties = new WriteServiceProperties();
        SchemaInitializer initializer = new SchemaInitializer(cqlSession, properties);

        initializer.initSchema();

        ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
        verify(cqlSession, times(5)).execute(statements.capture());

        List<String> executed = statements.getAllValues();
        assertThat(executed.get(0))
                .contains("CREATE KEYSPACE IF NOT EXISTS url_shortener")
                .contains("'class': 'SimpleStrategy'")
                .contains("'replication_factor': 1");
        assertThat(executed.get(1))
                .contains("ALTER KEYSPACE url_shortener")
                .contains("'class': 'SimpleStrategy'")
                .contains("'replication_factor': 1");
        assertThat(executed.get(2)).contains("CREATE TABLE IF NOT EXISTS url_shortener.url_mapping");
        assertThat(executed.get(3)).contains("CREATE TABLE IF NOT EXISTS url_shortener.alias_mapping");
        assertThat(executed.get(4)).contains("CREATE TABLE IF NOT EXISTS url_shortener.url_mapping_by_user");
    }

    @Test
    void initSchemaSupportsNetworkTopologyStrategyOverride() throws IOException {
        when(cqlSession.execute(anyString())).thenReturn(resultSet);

        WriteServiceProperties properties = new WriteServiceProperties();
        properties.getCassandra().setSchemaReplicationStrategy("NetworkTopologyStrategy");
        properties.getCassandra().setSchemaReplicationFactor(3);
        properties.getCassandra().setDatacenter("dc-prod");
        SchemaInitializer initializer = new SchemaInitializer(cqlSession, properties);

        initializer.initSchema();

        ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
        verify(cqlSession, times(5)).execute(statements.capture());

        assertThat(statements.getAllValues().get(0))
                .contains("CREATE KEYSPACE IF NOT EXISTS url_shortener")
                .contains("'class': 'NetworkTopologyStrategy'")
                .contains("'dc-prod': 3");
        assertThat(statements.getAllValues().get(1))
                .contains("ALTER KEYSPACE url_shortener")
                .contains("'class': 'NetworkTopologyStrategy'")
                .contains("'dc-prod': 3");
    }

    @Test
    void initSchemaResetsKeyspaceWhenAlterFailsWithIncompatibleReplicationFlavor() throws IOException {
        when(cqlSession.execute(anyString()))
                .thenReturn(resultSet)
                .thenThrow(new RuntimeException("Cannot alter replication strategy vnode/tablets flavor"))
                .thenReturn(resultSet)
                .thenReturn(resultSet)
                .thenReturn(resultSet)
                .thenReturn(resultSet)
                .thenReturn(resultSet);

        WriteServiceProperties properties = new WriteServiceProperties();
        properties.getCassandra().setSchemaResetOnIncompatibleReplication(true);
        SchemaInitializer initializer = new SchemaInitializer(cqlSession, properties);

        initializer.initSchema();

        ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
        verify(cqlSession, times(7)).execute(statements.capture());

        List<String> executed = statements.getAllValues();
        assertThat(executed.get(0)).contains("CREATE KEYSPACE IF NOT EXISTS url_shortener");
        assertThat(executed.get(1)).contains("ALTER KEYSPACE url_shortener");
        assertThat(executed.get(2)).contains("DROP KEYSPACE IF EXISTS url_shortener");
        assertThat(executed.get(3)).contains("CREATE KEYSPACE IF NOT EXISTS url_shortener");
        assertThat(executed.get(4)).contains("CREATE TABLE IF NOT EXISTS url_shortener.url_mapping");
        assertThat(executed.get(5)).contains("CREATE TABLE IF NOT EXISTS url_shortener.alias_mapping");
        assertThat(executed.get(6)).contains("CREATE TABLE IF NOT EXISTS url_shortener.url_mapping_by_user");
    }
}
