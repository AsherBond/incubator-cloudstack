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
package com.cloud.api.commands;

import org.apache.log4j.Logger;

import com.cloud.api.ApiConstants;
import com.cloud.api.BaseAsyncCmd;
import com.cloud.api.IdentityMapper;
import com.cloud.api.Implementation;
import com.cloud.api.Parameter;
import com.cloud.api.response.SuccessResponse;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.RemoteAccessVpn;
import com.cloud.user.UserContext;

@Implementation(description="Destroys a l2tp/ipsec remote access vpn", responseObject=SuccessResponse.class)
public class DeleteRemoteAccessVpnCmd extends BaseAsyncCmd {
    public static final Logger s_logger = Logger.getLogger(DeleteRemoteAccessVpnCmd.class.getName());

    private static final String s_name = "deleteremoteaccessvpnresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @IdentityMapper(entityTableName="user_ip_address")
    @Parameter(name=ApiConstants.PUBLIC_IP_ID, type=CommandType.LONG, required=true, description="public ip address id of the vpn server")
    private Long publicIpId;
    
    // unexposed parameter needed for events logging
    @IdentityMapper(entityTableName="account")
    @Parameter(name=ApiConstants.ACCOUNT_ID, type=CommandType.LONG, expose=false)
    private Long ownerId;
    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////
	
    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

	@Override
    public String getCommandName() {
        return s_name;
    }

	@Override
	public long getEntityOwnerId() {
	    if (ownerId == null) {
	    	RemoteAccessVpn vpnEntity = _entityMgr.findById(RemoteAccessVpn.class, publicIpId);
	    	if(vpnEntity != null)
	    		return vpnEntity.getAccountId();
	    	
	    	throw new InvalidParameterValueException("The specified public ip is not allocated to any account");
	    }
	    return ownerId;
    }

	@Override
	public String getEventDescription() {
		return "Delete Remote Access VPN for account " + getEntityOwnerId() + " for  ip id=" + publicIpId;
	}

	@Override
	public String getEventType() {
		return EventTypes.EVENT_REMOTE_ACCESS_VPN_DESTROY;
	}

    @Override
    public void execute() throws ResourceUnavailableException {
        _ravService.destroyRemoteAccessVpn(publicIpId, UserContext.current().getCaller());
    }
    
    @Override
    public String getSyncObjType() {
        return BaseAsyncCmd.networkSyncObject;
    }

    @Override
    public Long getSyncObjId() {
        return _ravService.getRemoteAccessVpn(publicIpId).getNetworkId();
    }
	
}
