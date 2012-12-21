/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.test;

import java.util.UUID;

import javax.inject.Inject;

import org.apache.cloudstack.storage.command.CopyTemplateToPrimaryStorageCmd;
import org.apache.cloudstack.storage.to.ImageDataStoreTO;
import org.apache.cloudstack.storage.to.ImageOnPrimayDataStoreTO;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.TemplateTO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.ReadyCommand;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.org.Cluster.ClusterType;
import com.cloud.org.Managed.ManagedState;
import com.cloud.resource.ResourceState;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations="classpath:/resource/storageContext.xml")
public class DirectAgentTest {
    @Inject
    AgentManager agentMgr;
    @Inject 
    HostDao hostDao;
    @Inject
    HostPodDao podDao;
    @Inject
    ClusterDao clusterDao;
    @Inject
    DataCenterDao dcDao;
    private long dcId;
    private long clusterId;
    private long hostId;
    private String hostGuid = "9d4c9db8-32f7-25c3-0435-eab4bf3adcea";
    @Before
    public void setUp() {
        HostVO host = hostDao.findByGuid(hostGuid);
        if (host != null) {
            hostId = host.getId();
            dcId = host.getDataCenterId();
            clusterId = host.getClusterId();
            return;
        }
        //create data center
        DataCenterVO dc = new DataCenterVO(UUID.randomUUID().toString(), "test", "8.8.8.8", null, "10.0.0.1", null,  "10.0.0.1/24", 
                null, null, NetworkType.Basic, null, null, true,  true);
        dc = dcDao.persist(dc);
        dcId = dc.getId();
        //create pod

        HostPodVO pod = new HostPodVO(UUID.randomUUID().toString(), dc.getId(), "192.168.56.1", "192.168.56.0/24", 8, "test");
        pod = podDao.persist(pod);
        //create xen cluster
        ClusterVO cluster = new ClusterVO(dc.getId(), pod.getId(), "devcloud cluster");
        cluster.setHypervisorType(HypervisorType.XenServer.toString());
        cluster.setClusterType(ClusterType.CloudManaged);
        cluster.setManagedState(ManagedState.Managed);
        cluster = clusterDao.persist(cluster);
        clusterId = cluster.getId();
        //create xen host

        //TODO: this hardcode host uuid in devcloud
        host = new HostVO(hostGuid);
        host.setName("devcloud xen host");
        host.setType(Host.Type.Routing);
        host.setHypervisorType(HypervisorType.XenServer);
        host.setPrivateIpAddress("192.168.56.10");
        host.setDataCenterId(dc.getId());
        host.setVersion("6.0.1");
        host.setAvailable(true);
        host.setSetup(true);
        host.setLastPinged(0);
        host.setResourceState(ResourceState.Enabled);
        host.setClusterId(cluster.getId());

        host = hostDao.persist(host);
        hostId = host.getId();
    }
    
    @Test
    public void testInitResource() {
        ReadyCommand cmd = new ReadyCommand(dcId);
        try {
            agentMgr.send(hostId, cmd);
        } catch (AgentUnavailableException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (OperationTimedoutException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
    
    @Test
    public void testDownloadTemplate() {
        ImageOnPrimayDataStoreTO image = Mockito.mock(ImageOnPrimayDataStoreTO.class);
        PrimaryDataStoreTO primaryStore = Mockito.mock(PrimaryDataStoreTO.class);
        Mockito.when(primaryStore.getUuid()).thenReturn("9f3f9262-3f77-09cc-2df7-0d8475676260");
        Mockito.when(image.getPrimaryDataStore()).thenReturn(primaryStore);
        
        ImageDataStoreTO imageStore = Mockito.mock(ImageDataStoreTO.class);
        Mockito.when(imageStore.getType()).thenReturn("http");
        
        TemplateTO template = Mockito.mock(TemplateTO.class);
        Mockito.when(template.getPath()).thenReturn("http://download.cloud.com/templates/devcloud/defaulttemplates/5/ce5b212e-215a-3461-94fb-814a635b2215.vhd");
        Mockito.when(template.getImageDataStore()).thenReturn(imageStore);
        
        Mockito.when(image.getTemplate()).thenReturn(template);
        CopyTemplateToPrimaryStorageCmd cmd = new CopyTemplateToPrimaryStorageCmd(image);
        try {
            agentMgr.send(hostId, cmd);
        } catch (AgentUnavailableException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (OperationTimedoutException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
}
