package com.neo4j.docker.neo4jadmin;

import com.neo4j.docker.utils.DatabaseIO;
import com.neo4j.docker.utils.HostFileSystemOperations;
import com.neo4j.docker.utils.SetContainerUser;
import com.neo4j.docker.utils.TestSettings;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.file.Path;
import java.time.Duration;

public class TestBackupRestore
{
    // with authentication
    // with non-default user
    private static final Logger log = LoggerFactory.getLogger( TestBackupRestore.class );

    @BeforeAll
    static void ensureEnterpriseOnly()
    {
        Assumptions.assumeTrue( TestSettings.EDITION == TestSettings.Edition.ENTERPRISE,
                                "backup and restore only available in Neo4j Enterprise" );
    }

    private GenericContainer createDBContainer( boolean asDefaultUser, String password )
    {
        String auth = "none";
        if(!password.equalsIgnoreCase("none"))
        {
            auth = "neo4j/"+password;
        }

        GenericContainer container = new GenericContainer( TestSettings.IMAGE_ID );
        container.withEnv( "NEO4J_AUTH", auth )
                 .withEnv( "NEO4J_ACCEPT_LICENSE_AGREEMENT", "yes" )
                 .withEnv( "NEO4J_dbms_backup_enabled", "true" )
                 .withEnv( "NEO4J_dbms_backup_listen__address", "0.0.0.0:6362" )
                 .withExposedPorts( 7474, 7687, 6362 )
                 .withLogConsumer( new Slf4jLogConsumer( log ) )
                 .waitingFor( Wait.forHttp( "/" )
                                  .forPort( 7474 )
                                  .forStatusCode( 200 )
                                  .withStartupTimeout( Duration.ofSeconds( 90 ) ) );
        if(!asDefaultUser)
        {
            SetContainerUser.nonRootUser( container );
        }
        return container;
    }

    private GenericContainer createAdminContainer( boolean asDefaultUser )
    {
        GenericContainer container = new GenericContainer( TestSettings.ADMIN_IMAGE_ID );
        container.withEnv( "NEO4J_ACCEPT_LICENSE_AGREEMENT", "yes" )
                 .withLogConsumer( new Slf4jLogConsumer( log ) )
                 .withStartupCheckStrategy( new OneShotStartupCheckStrategy().withTimeout( Duration.ofSeconds( 90 ) ) );
        if(!asDefaultUser)
        {
            SetContainerUser.nonRootUser( container );
        }
        return container;
    }

    @Test
    void shouldBackupAndRestore_defaultUser_noAuth() throws Exception
    {
        testCanBackupAndRestore( true, "none" );
    }
    @Test
    void shouldBackupAndRestore_nonDefaultUser_noAuth() throws Exception
    {
        testCanBackupAndRestore( false, "none" );
    }
    @Test
    void shouldBackupAndRestore_defaultUser_withAuth() throws Exception
    {
        testCanBackupAndRestore( true, "secretpassword" );
    }
    @Test
    void shouldBackupAndRestore_nonDefaultUser_withAuth() throws Exception
    {
        testCanBackupAndRestore( false, "secretpassword" );
    }

    private void testCanBackupAndRestore(boolean asDefaultUser, String password) throws Exception
    {
        String dbUser = "neo4j";
        Path testOutputFolder = HostFileSystemOperations.createTempFolder( "backupRestore-" );

        // BACKUP
        // start a database and populate data
        GenericContainer neo4j = createDBContainer( asDefaultUser, password );
        Path dataDir = HostFileSystemOperations.createTempFolderAndMountAsVolume(
                neo4j, "data-", "/data", testOutputFolder );
        neo4j.start();
        DatabaseIO dbio = new DatabaseIO( neo4j );
        dbio.putInitialDataIntoContainer( dbUser, password );
        dbio.verifyInitialDataInContainer( dbUser, password );

        // start admin container to initiate backup
        GenericContainer adminBackup = createAdminContainer( asDefaultUser )
                .withNetworkMode( "container:"+neo4j.getContainerId() )
                .waitingFor( new LogMessageWaitStrategy().withRegEx( "^Backup complete successful.*" ) )
                .withCommand( "neo4j-admin", "backup", "--database=neo4j", "--backup-dir=/backup");

        Path backupDir = HostFileSystemOperations.createTempFolderAndMountAsVolume(
                adminBackup, "backup-", "/backup", testOutputFolder );
        adminBackup.start();

        Assertions.assertTrue( neo4j.isRunning(), "neo4j container should still be running" );
        dbio.verifyInitialDataInContainer( dbUser, password );
        adminBackup.stop();

        // RESTORE

        // write more stuff
        dbio.putMoreDataIntoContainer( dbUser, password );
        dbio.verifyMoreDataIntoContainer( dbUser, password, true );

        // do restore
        dbio.runCypherQuery( dbUser, password, "STOP DATABASE neo4j", "system" );
        GenericContainer adminRestore = createAdminContainer( asDefaultUser )
                .waitingFor( new LogMessageWaitStrategy().withRegEx( "^.*restoreStatus=successful.*" ) )
                .withCommand( "neo4j-admin", "restore", "--database=neo4j", "--from=/backup/neo4j", "--force");
        HostFileSystemOperations.mountHostFolderAsVolume( adminRestore, backupDir, "/backup" );
        HostFileSystemOperations.mountHostFolderAsVolume( adminRestore, dataDir, "/data" );
        adminRestore.start();
        dbio.runCypherQuery( dbUser, password, "START DATABASE neo4j", "system" );

        // verify new stuff is missing
        dbio.verifyMoreDataIntoContainer( dbUser, password, false );

        // clean up
        adminRestore.stop();
        neo4j.stop();
    }

    @Test
    void deleteThis()
    {
        boolean asDefaultUser = true;
        String dbUser = "neo4j";
        String password = "none";
        try(GenericContainer neo4j = createDBContainer( asDefaultUser, password ))
        {
            neo4j.start();
            DatabaseIO dbio = new DatabaseIO( neo4j );
            dbio.putInitialDataIntoContainer( dbUser, password );
            dbio.verifyInitialDataInContainer( dbUser, password );
            dbio.runCypherQuery( dbUser, password, "DROP DATABASE neo4j", "system" );
            dbio.verifyConnectivity( dbUser, password );
        }
    }
}