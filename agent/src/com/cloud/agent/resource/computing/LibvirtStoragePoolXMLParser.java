// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.agent.resource.computing;

import java.io.IOException;
import java.io.StringReader;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.log4j.Logger;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class LibvirtStoragePoolXMLParser {
    private static final Logger s_logger = Logger
            .getLogger(LibvirtStoragePoolXMLParser.class);

    public LibvirtStoragePoolDef parseStoragePoolXML(String poolXML) {
        DocumentBuilder builder;
        try {
            builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();

            InputSource is = new InputSource();
            is.setCharacterStream(new StringReader(poolXML));
            Document doc = builder.parse(is);

            Element rootElement = doc.getDocumentElement();
            String type = rootElement.getAttribute("type");

            String uuid = getTagValue("uuid", rootElement);

            String poolName = getTagValue("name", rootElement);

            Element source = (Element) rootElement.getElementsByTagName(
                    "source").item(0);
            String host = getAttrValue("host", "name", source);

            if (type.equalsIgnoreCase("rbd")) {
                int port = Integer.parseInt(getAttrValue("host", "port", source));
                String pool = getTagValue("name", source);

                Element auth = (Element) source.getElementsByTagName(
                    "auth").item(0);

                if (auth != null) {
                    String authUsername = auth.getAttribute("username");
                    String authType = auth.getAttribute("type");
                    return new LibvirtStoragePoolDef(LibvirtStoragePoolDef.poolType.valueOf(type.toUpperCase()),
                        poolName, uuid, host, port, pool, authUsername, LibvirtStoragePoolDef.authType.valueOf(authType.toUpperCase()), uuid);
                } else {
                    return new LibvirtStoragePoolDef(LibvirtStoragePoolDef.poolType.valueOf(type.toUpperCase()),
                        poolName, uuid, host, port, pool, "");
                }
            } else {
                String path = getAttrValue("dir", "path", source);

                Element target = (Element) rootElement.getElementsByTagName(
                        "target").item(0);
                String targetPath = getTagValue("path", target);

                return new LibvirtStoragePoolDef(
                        LibvirtStoragePoolDef.poolType.valueOf(type.toUpperCase()),
                        poolName, uuid, host, path, targetPath);
            }
        } catch (ParserConfigurationException e) {
            s_logger.debug(e.toString());
        } catch (SAXException e) {
            s_logger.debug(e.toString());
        } catch (IOException e) {
            s_logger.debug(e.toString());
        }
        return null;
    }

    private static String getTagValue(String tag, Element eElement) {
        NodeList nlList = eElement.getElementsByTagName(tag).item(0)
                .getChildNodes();
        Node nValue = (Node) nlList.item(0);

        return nValue.getNodeValue();
    }

    private static String getAttrValue(String tag, String attr, Element eElement) {
        NodeList tagNode = eElement.getElementsByTagName(tag);
        if (tagNode.getLength() == 0) {
            return null;
        }
        Element node = (Element) tagNode.item(0);
        return node.getAttribute(attr);
    }

    public static void main(String[] args) {
        s_logger.addAppender(new org.apache.log4j.ConsoleAppender(
                new org.apache.log4j.PatternLayout(), "System.out"));
        String storagePool = "<pool type='dir'>" + "<name>test</name>"
                + "<uuid>bf723c83-4b95-259c-7089-60776e61a11f</uuid>"
                + "<capacity>20314165248</capacity>"
                + "<allocation>1955450880</allocation>"
                + "<available>18358714368</available>" + "<source>"
                + "<host name='nfs1.lab.vmops.com'/>"
                + "<dir path='/export/home/edison/kvm/primary'/>"
                + "<format type='auto'/>" + "</source>" + "<target>"
                + "<path>/media</path>" + "<permissions>" + "<mode>0700</mode>"
                + "<owner>0</owner>" + "<group>0</group>" + "</permissions>"
                + "</target>" + "</pool>";

        LibvirtStoragePoolXMLParser parser = new LibvirtStoragePoolXMLParser();
        LibvirtStoragePoolDef pool = parser.parseStoragePoolXML(storagePool);
        s_logger.debug(pool.toString());
    }
}
