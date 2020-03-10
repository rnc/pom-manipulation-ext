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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectVersionRef;
import org.commonjava.maven.ext.io.rest.handler.AddSuffixJettyHandler;
import org.commonjava.maven.ext.io.rest.mapper.GAVSchema;
import org.commonjava.maven.ext.io.rest.rule.MockServer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.commonjava.maven.ext.io.rest.Translator.RestProtocol;
import static org.junit.Assert.*;

public class RESTParametersTest
{
    private static final Logger LOGGER = LoggerFactory.getLogger( AddSuffixJettyHandler.class );

    private final String group = "indyGroup";

    private final String suffix = "rebuild";

    private DefaultTranslator versionTranslator;

    private GAVSchema gavSchema;

    @Rule
    public final TestName testName = new TestName();

    @Rule
    public final MockServer mockServer = new MockServer( new AbstractHandler()
    {
        @Override
        public void handle( String target, Request baseRequest, HttpServletRequest request,
                            HttpServletResponse response ) throws IOException, ServletException
        {
            ObjectMapper objectMapper = new ObjectMapper();
            StringBuilder jb = new StringBuilder();
            String line;
            BufferedReader reader = request.getReader();
            while ( ( line = reader.readLine() ) != null )
            {
                jb.append( line );
            }
            gavSchema = objectMapper.readValue( jb.toString(), GAVSchema.class );
            LOGGER.info( "Read request body '{}' and read parameters '{}' and Group {} ", jb, request.getParameterMap(), gavSchema.repositoryGroup );
            baseRequest.setHandled( true );

        }
    } );

    @Before
    public void before()
    {
        LoggerFactory.getLogger( RESTParametersTest.class ).info( "Executing test " + testName.getMethodName() );
    }

    @Test
    public void testVerifyGroup()
    {
        this.versionTranslator = new DefaultTranslator( mockServer.getUrl(), RestProtocol.CURRENT, 0,
                                                        Translator.CHUNK_SPLIT_COUNT, group, "", DefaultTranslator.DEFAULT_CONNECTION_TIMEOUT_SEC, 
                                                        DefaultTranslator.DEFAULT_SOCKET_TIMEOUT_SEC, DefaultTranslator.RETRY_DURATION_SEC );
        List<ProjectVersionRef> gavs = Collections.singletonList(
            new SimpleProjectVersionRef( "com.example", "example", "1.0" ) );

        versionTranslator.translateVersions( gavs );
        assertEquals( group, gavSchema.repositoryGroup );
    }

    @Test
    public void testVerifyNoGroup()
    {
        this.versionTranslator = new DefaultTranslator( mockServer.getUrl(), RestProtocol.CURRENT, 0,
                                                        Translator.CHUNK_SPLIT_COUNT, "", "", DefaultTranslator.DEFAULT_CONNECTION_TIMEOUT_SEC, 
                                                        DefaultTranslator.DEFAULT_SOCKET_TIMEOUT_SEC, DefaultTranslator.RETRY_DURATION_SEC );
        List<ProjectVersionRef> gavs = Collections.singletonList(
            new SimpleProjectVersionRef( "com.example", "example", "1.0" ) );

        versionTranslator.translateVersions( gavs );
        assertNull( gavSchema.repositoryGroup );
    }
}
