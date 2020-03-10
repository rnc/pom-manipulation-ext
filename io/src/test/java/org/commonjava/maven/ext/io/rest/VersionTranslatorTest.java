/*
 * Copyright (C) 2012 Red Hat, Inc. (jcasey@redhat.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.maven.ext.io.rest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mashape.unirest.http.Unirest;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectVersionRef;
import org.commonjava.maven.ext.io.rest.exception.RestException;
import org.commonjava.maven.ext.io.rest.handler.AddSuffixJettyHandler;
import org.commonjava.maven.ext.io.rest.rule.MockServer;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.junit.runners.Parameterized;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

import static org.commonjava.maven.ext.io.rest.Translator.RestProtocol;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/**
 * @author vdedik@redhat.com
 */
@FixMethodOrder( MethodSorters.NAME_ASCENDING)
@RunWith( Parameterized.class)
public class VersionTranslatorTest
{
    private static List<ProjectVersionRef> aLotOfGavs;

    private DefaultTranslator versionTranslator;

    private RestProtocol protocol;

    @Parameterized.Parameters()
    public static Collection<Object[]> data()
    {
        return Arrays.asList( new Object[][] { { RestProtocol.CURRENT } } );
    }

    @Rule
    public TestName testName = new TestName();

    @Rule
    public MockServer mockServer = new MockServer( new AddSuffixJettyHandler() );

    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();

    @BeforeClass
    public static void startUp()
                    throws IOException
    {
        aLotOfGavs = loadALotOfGAVs();
    }

    @Before
    public void before()
    {
        LoggerFactory.getLogger( VersionTranslatorTest.class ).info( "Executing test " + testName.getMethodName() );

        this.versionTranslator = new DefaultTranslator( mockServer.getUrl(), protocol, 0, Translator.CHUNK_SPLIT_COUNT, "indyGroup",
                                                        "", DefaultTranslator.DEFAULT_CONNECTION_TIMEOUT_SEC, 
                                                        DefaultTranslator.DEFAULT_SOCKET_TIMEOUT_SEC, DefaultTranslator.RETRY_DURATION_SEC );
    }

    public VersionTranslatorTest( RestProtocol protocol)
    {
        this.protocol = protocol;
    }

    @Test
    public void testConnection()
    {
        try
        {
            Unirest.post( mockServer.getUrl() ).asString();
        }
        catch ( Exception e )
        {
            fail( "Failed to connect to server, exception message: " + e.getMessage() );
        }
    }

    @Test
    public void testTranslateVersions()
    {
        List<ProjectVersionRef> gavs = Arrays.asList(
            new SimpleProjectVersionRef( "com.example", "example", "1.0" ),
            new SimpleProjectVersionRef( "com.example", "example-dep", "2.0" ),
            new SimpleProjectVersionRef( "org.commonjava", "example", "1.0" ),
            new SimpleProjectVersionRef( "org.commonjava", "example", "1.1" ));

        Map<ProjectVersionRef, String> actualResult = versionTranslator.translateVersions( gavs );
        Map<ProjectVersionRef, String> expectedResult = new HashMap<ProjectVersionRef, String>()
        {{
            put( new SimpleProjectVersionRef( "com.example", "example", "1.0" ), "1.0-redhat-1" );
            put( new SimpleProjectVersionRef( "com.example", "example-dep", "2.0" ), "2.0-redhat-1" );
            put( new SimpleProjectVersionRef( "org.commonjava", "example", "1.0" ), "1.0-redhat-1" );
            put( new SimpleProjectVersionRef( "org.commonjava", "example", "1.1" ), "1.1-redhat-1" );
        }};

        assertThat( actualResult, is( expectedResult ) );
    }

    @Test
    public void testTranslateVersionsFailNoResponse()
    {
        // Some url that doesn't exist used here
        Translator translator = new DefaultTranslator( "http://127.0.0.2", RestProtocol.CURRENT, 0,
                                                       Translator.CHUNK_SPLIT_COUNT, "",
                                                       "", DefaultTranslator.DEFAULT_CONNECTION_TIMEOUT_SEC, 
                                                       DefaultTranslator.DEFAULT_SOCKET_TIMEOUT_SEC, DefaultTranslator.RETRY_DURATION_SEC );

        List<ProjectVersionRef> gavs = Collections.singletonList(
            new SimpleProjectVersionRef( "com.example", "example", "1.0" ) );

        try
        {
            translator.translateVersions( gavs );
            fail( "Failed to throw RestException when server failed to respond." );
        }
        catch ( RestException ex )
        {
            System.out.println( "Caught ex" + ex );
            // Pass
        }
        catch ( Exception ex )
        {
            fail( String.format( "Expected exception is RestException, instead %s thrown.",
                                 ex.getClass().getSimpleName() ) );
        }
    }

    @Test( timeout = 2000 )
    public void testTranslateVersionsPerformance()
    {
        // Disable logging for this test as impacts timing.
        ( (Logger) LoggerFactory.getLogger( Logger.ROOT_LOGGER_NAME ) ).setLevel( Level.WARN );

        versionTranslator.translateVersions( aLotOfGavs );
    }


    private static String readFileFromClasspath( String filename )
    {
        StringBuilder fileContents = new StringBuilder();
        String lineSeparator = System.getProperty( "line.separator" );

        try (Scanner scanner = new Scanner( VersionTranslatorTest.class.getResourceAsStream( filename ) ))
        {
            while ( scanner.hasNextLine() )
            {
                fileContents.append( scanner.nextLine() ).append( lineSeparator );
            }
            return fileContents.toString();
        }
    }


    static List<ProjectVersionRef> loadALotOfGAVs() throws IOException {
        List<ProjectVersionRef> result = new ArrayList<>();
        String longJsonFile = readFileFromClasspath( "example-response-performance-test.json" );

        ObjectMapper objectMapper = new ObjectMapper();
        List<Map<String, String>> gavs = objectMapper
                .readValue( longJsonFile, new TypeReference<List<Map<String, String>>>() {} );

        for ( Map<String, String> gav : gavs )
        {
            ProjectVersionRef project = new SimpleProjectVersionRef( gav.get( "groupId" ), gav.get( "artifactId" ), gav.get( "version" ) );
            result.add( project );
        }
        return result;
    }
}
