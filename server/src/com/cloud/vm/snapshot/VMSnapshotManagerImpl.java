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

package com.cloud.vm.snapshot;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.ejb.Local;
import javax.naming.ConfigurationException;

import org.apache.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.CreateVMSnapshotAnswer;
import com.cloud.agent.api.CreateVMSnapshotCommand;
import com.cloud.agent.api.DeleteVMSnapshotAnswer;
import com.cloud.agent.api.DeleteVMSnapshotCommand;
import com.cloud.agent.api.RevertToVMSnapshotAnswer;
import com.cloud.agent.api.RevertToVMSnapshotCommand;
import com.cloud.agent.api.VMSnapshotTO;
import com.cloud.agent.api.to.VolumeTO;
import com.cloud.api.commands.CreateVMSnapshotCmd;
import com.cloud.api.commands.DeleteVMSnapshotCmd;
import com.cloud.api.commands.ListVmSnapshotCmd;
import com.cloud.api.commands.RevertToSnapshotCmd;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.configuration.dao.ConfigurationDao;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.event.dao.UsageEventDao;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.network.NetworkManager;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.security.SecurityGroupManager;
import com.cloud.network.security.dao.SecurityGroupDao;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.StoragePoolVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.StoragePoolDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateDetailsDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.UserContext;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.SSHKeyPairDao;
import com.cloud.user.dao.UserDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.DateUtil;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.Adapters;
import com.cloud.utils.component.ComponentLocator;
import com.cloud.utils.component.Inject;
import com.cloud.utils.component.Manager;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.Event;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.VMSnapshot.Type;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

@Local(value = { VMSnapshotManager.class, VMSnapshotService.class })
public class VMSnapshotManagerImpl implements VMSnapshotManager, VMSnapshotService, Manager {
    private static final Logger s_logger = Logger.getLogger(VMSnapshotManagerImpl.class);
    String _name;
    @Inject
    protected VMSnapshotDao _vmSnapshotDao;
    @Inject
    protected VolumeDao _volumeDao;
    @Inject
    protected AccountDao _accountDao;
    @Inject
    protected VMInstanceDao _vmInstanceDao;
    @Inject
    protected UserVmDao _userVMDao;
    @Inject
    protected HostDao _hostDao;
    @Inject
    protected ClusterDao _clusterDao;
    @Inject
    protected UserDao _userDao;
    @Inject
    protected AgentManager _agentMgr;
    @Inject
    protected HypervisorGuruManager _hvGuruMgr;
    @Inject
    protected AccountManager _accountMgr;
    @Inject
    protected VolumeDao _volsDao = null;
    @Inject
    protected GuestOSDao _guestOSDao = null;
    @Inject
    protected VMTemplateDao _templateDao = null;
    @Inject
    protected VMTemplateDetailsDao _templateDetailsDao = null;
    @Inject
    private StoragePoolDao _storagePoolDao;
    @Inject
    protected NetworkManager _networkMgr = null;
    @Inject
    protected NetworkDao _networkDao = null;
    @Inject
    protected SecurityGroupManager _securityGroupMgr;
    @Inject
    protected SecurityGroupDao _securityGroupDao;
    @Inject
    protected ConfigurationManager _configMgr = null;
    @Inject
    protected NetworkOfferingDao _networkOfferingDao;
    @Inject
    protected DomainDao _domainDao;
    @Inject
    protected ServiceOfferingDao _serviceOfferingDao;
    @Inject
    protected SSHKeyPairDao _sshKeyPairDao;
    @Inject
    protected UserVmDao _vmDao = null;
    @Inject
    protected UsageEventDao _usageEventDao;
    @Inject
    protected VMTemplateDao _vmTemplateDao;
    @Inject
    UserVmManager _userVmMgr;
    @Inject
    protected SnapshotDao _snapshotDao = null;
    @Inject
    protected HighAvailabilityManager _haMgr;
    @Inject
    GuestOSDao _guestOsDao;

    @Inject
    VirtualMachineManager _itMgr;

    @Inject(adapter = DeploymentPlanner.class)
    protected Adapters<DeploymentPlanner> _planners;

    private ConfigurationDao _configDao;
    protected String _instance;
    protected int _vmSnapshotMax;
    protected StateMachine2<State, VirtualMachine.Event, VirtualMachine> _stateMachine;
    private StateMachine2<VMSnapshot.State, VMSnapshot.Event, VMSnapshot> _vmSnapshottateMachine ;
    ScheduledExecutorService _executor = null;
    int _snapshotCleanupInterval;

    @Inject
    protected ServiceOfferingDao _offeringDao;

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        _name = name;

        ComponentLocator locator = ComponentLocator.getCurrentLocator();
        _configDao = locator.getDao(ConfigurationDao.class);
        if (_configDao == null) {
            throw new ConfigurationException("Unable to get the configuration dao.");
        }
        s_logger.info("Snapshot Manager is configured.");

        Map<String, String> configs = _configDao.getConfiguration("AgentManager", params);
        _instance = configs.get("instance.name");

        _vmSnapshotMax = NumbersUtil.parseInt(_configDao.getValue("vmsnapshot.max"), VMSNAPSHOTMAX);

        _stateMachine = VirtualMachine.State.getStateMachine();

        String workers = configs.get("vmsnapshot.expunge.workers");
        int wrks = NumbersUtil.parseInt(workers, 10);
        _executor = Executors.newScheduledThreadPool(wrks, new NamedThreadFactory("VMSnapshotManager-Scavenger"));

        String time = configs.get("vmsnapshot.expunge.interval");
        _snapshotCleanupInterval = NumbersUtil.parseInt(time, 86400);

        return true;
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    public List<VMSnapshotVO> listVMSnapshots(ListVmSnapshotCmd cmd) {
        Account caller = UserContext.current().getCaller();
        List<Long> permittedAccounts = new ArrayList<Long>();

        boolean listAll = cmd.listAll();
        Long id = cmd.getId();
        Long vmId = cmd.getVmId();
        String state = cmd.getState();
        String keyword = cmd.getKeyword();
        String name = cmd.getVmSnapshotName();
        String accountName = cmd.getAccountName();

        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<Long, Boolean, ListProjectResourcesCriteria>(
                cmd.getDomainId(), cmd.isRecursive(), null);
        _accountMgr.buildACLSearchParameters(caller, id, cmd.getAccountName(), cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, listAll,
                false);
        Long domainId = domainIdRecursiveListProject.first();
        Boolean isRecursive = domainIdRecursiveListProject.second();
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();

        Filter searchFilter = new Filter(VMSnapshotVO.class, "created", false, cmd.getStartIndex(), cmd.getPageSizeVal());
        SearchBuilder<VMSnapshotVO> sb = _vmSnapshotDao.createSearchBuilder();
        _accountMgr.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        sb.and("vm_id", sb.entity().getVmId(), SearchCriteria.Op.EQ);
        sb.and("domain_id", sb.entity().getDomainId(), SearchCriteria.Op.EQ);
        sb.and("status", sb.entity().getState(), SearchCriteria.Op.IN);
        sb.and("state", sb.entity().getState(), SearchCriteria.Op.EQ);
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("display_name", sb.entity().getDisplayName(), SearchCriteria.Op.EQ);
        sb.and("account_id", sb.entity().getAccountId(), SearchCriteria.Op.EQ);
        sb.done();

        SearchCriteria<VMSnapshotVO> sc = sb.create();
        _accountMgr.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (accountName != null && cmd.getDomainId() != null) {
            Account account = _accountMgr.getActiveAccountByName(accountName, cmd.getDomainId());
            sc.setParameters("account_id", account.getId());
        }

        if (vmId != null) {
            sc.setParameters("vm_id", vmId);
        }

        if (domainId != null) {
            sc.setParameters("domain_id", domainId);
        }

        if (state == null) {
            VMSnapshot.State[] status = { VMSnapshot.State.Ready, VMSnapshot.State.Creating, VMSnapshot.State.Allocated, 
                    VMSnapshot.State.Error, VMSnapshot.State.Expunging, VMSnapshot.State.Reverting };
            sc.setParameters("status", (Object[]) status);
        } else {
            sc.setParameters("state", state);
        }

        if (name != null) {
            sc.setParameters("display_name", name);
        }

        if (keyword != null) {
            SearchCriteria<VMSnapshotVO> ssc = _vmSnapshotDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("display_name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("description", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        return _vmSnapshotDao.search(sc, searchFilter);
    }

    @Override
    public VMSnapshot allocVMSnapshot(CreateVMSnapshotCmd cmd) throws ResourceAllocationException {
        Long vmId = cmd.getVmId();
        String vsDisplayName = cmd.getDisplayName();
        String vsDescription = cmd.getDescription();
        Boolean snapshotMemory = cmd.snapshotMemory();

        Account caller = UserContext.current().getCaller();

        // check if VM exists
        UserVmVO userVmVo = _userVMDao.findById(vmId);
        if (userVmVo == null) {
            throw new InvalidParameterValueException("Creating VM snapshot failed due to VM:" + vmId + " is a system VM or does not exist");
        }
        
        // VM snapshot display name must be unique for a VM
        String timeString = DateUtil.getDateDisplayString(DateUtil.GMT_TIMEZONE, new Date(), DateUtil.YYYYMMDD_FORMAT);
        String vmSnapshotName = userVmVo.getInstanceName() + "_VS_" + timeString;
        if (vsDisplayName == null) {
            vsDisplayName = vmSnapshotName;
        }
        if(_vmSnapshotDao.findByName(vmId,vsDisplayName) != null){
            throw new InvalidParameterValueException("Creating VM snapshot failed due to VM snapshot with name" + vsDisplayName + "  already exists"); 
        }
        
        // check VM state
        if (userVmVo.getState() != VirtualMachine.State.Running && userVmVo.getState() != VirtualMachine.State.Stopped) {
            throw new InvalidParameterValueException("Creating vm snapshot failed due to VM:" + vmId + " is not in the running or Stopped state");
        }
        
        // for KVM, only allow snapshot with memory when VM is in running state
        if(userVmVo.getHypervisorType() == HypervisorType.KVM && userVmVo.getState() == State.Running && !snapshotMemory){
            throw new InvalidParameterValueException("KVM VM does not allow to take a disk-only snapshot when VM is in running state");
        }
        
        // check access
        _accountMgr.checkAccess(caller, null, true, userVmVo);

        // check max snapshot limit for per VM
        if (_vmSnapshotDao.findByVm(vmId).size() >= _vmSnapshotMax) {
            throw new InvalidParameterValueException("Creating vm snapshot failed due to a VM can just have : " + _vmSnapshotMax
                    + " VM snapshots. Please delete old ones");
        }

        // check if there are active volume snapshots tasks
        List<VolumeVO> listVolumes = _volumeDao.findByInstance(vmId);
        for (VolumeVO volume : listVolumes) {
            List<SnapshotVO> activeSnapshots = _snapshotDao.listByInstanceId(volume.getInstanceId(), Snapshot.Status.Creating,
                    Snapshot.Status.CreatedOnPrimary, Snapshot.Status.BackingUp);
            if (activeSnapshots.size() > 0) {
                throw new CloudRuntimeException(
                        "There is other active volume snapshot tasks on the instance to which the volume is attached, please try again later.");
            }
        }
        
        // check if there are other active VM snapshot tasks
        if (checkActiveVMSnapshotTasks(vmId)) {
            throw new InvalidParameterValueException("There is other active vm snapshot tasks on the instance, please try again later");
        }
        
        VMSnapshot.Type vmSnapshotType = Type.Disk;
        if(snapshotMemory && userVmVo.getState() == VirtualMachine.State.Running)
            vmSnapshotType = Type.DiskAndMemory;
        
        try {
            VMSnapshotVO vmSnapshotVo = new VMSnapshotVO(userVmVo.getAccountId(), userVmVo.getDomainId(), vmId, vsDescription, vmSnapshotName,
                    vsDisplayName, userVmVo.getServiceOfferingId(), vmSnapshotType, null);
            VMSnapshot vmSnapshot = _vmSnapshotDao.persist(vmSnapshotVo);
            if (vmSnapshot == null) {
                throw new CloudRuntimeException("Failed to create snapshot for vm: " + vmId);
            }
            return vmSnapshot;
        } catch (Exception e) {
            String msg = e.getMessage();
            s_logger.error("Create vm snapshot record failed for vm: " + vmId + " due to: " + msg);
        }  
        return null;
    }

    @Override
    public String getName() {
        return _name;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_SNAPSHOT_CREATE, eventDescription = "creating_vm_snapshot", async = true)
    public VMSnapshot creatVMSnapshot(CreateVMSnapshotCmd cmd) {
        Long vmId = cmd.getVmId();
        UserVmVO userVm = _userVMDao.findById(vmId);
        VMSnapshotVO vmSnapshot = _vmSnapshotDao.findById(cmd.getEntityId());
        Long hostId = pickRunningHost(vmId);
        transitState(vmSnapshot, VMSnapshot.Event.CreateRequested, userVm,  Event.SnapshotRequested, hostId);
        return createVmSnapshotInternal(userVm, vmSnapshot, hostId);
    }

    protected VMSnapshot createVmSnapshotInternal(UserVmVO userVm, VMSnapshotVO vmSnapshot, Long hostId) {
        try { 
            CreateVMSnapshotAnswer answer = null;
            GuestOSVO guestOS = _guestOsDao.findById(userVm.getGuestOSId());
            
            // prepare snapshotVolumeTos
            List<VolumeTO> volumeTOs = getVolumeTOList(userVm.getId());
            
            // prepare target snapshotTO and its parent snapshot (current snapshot)
            VMSnapshotTO current = null;
            VMSnapshotVO currentSnapshot = _vmSnapshotDao.findCurrentSnapshotByVmId(userVm.getId());
            if (currentSnapshot != null)
                current = getSnapshotWithParents(currentSnapshot);
            VMSnapshotTO target = new VMSnapshotTO(vmSnapshot.getId(),  vmSnapshot.getName(), vmSnapshot.getType(), null, vmSnapshot.getDescription(), false,
                    current);
            if (current == null)
                vmSnapshot.setParent(null);
            else
                vmSnapshot.setParent(current.getId());

            CreateVMSnapshotCommand ccmd = new CreateVMSnapshotCommand(userVm.getInstanceName(),target ,volumeTOs, guestOS.getDisplayName(),userVm.getState());
            
            answer = (CreateVMSnapshotAnswer) sendToPool(hostId, ccmd);
            if (answer != null && answer.getResult()) {
                processAnswer(vmSnapshot, userVm, answer, hostId);
                transitState(vmSnapshot, VMSnapshot.Event.OperationSucceeded, userVm, Event.OperationSucceeded, hostId);
                s_logger.debug("Create vm snapshot " + vmSnapshot.getName() + " succeeded for vm: " + userVm.getInstanceName());
            }else{
                String errMsg = answer.getDetails();
                s_logger.error("Agent reports creating vm snapshot " + vmSnapshot.getName() + " failed for vm: " + userVm.getInstanceName() + " due to " + errMsg);
                transitState(vmSnapshot, VMSnapshot.Event.OperationFailed, userVm,  Event.OperationFailed, hostId);
            }
            return vmSnapshot;
        } catch (Exception e) {
            String msg = e.getMessage();
            s_logger.error("Create vm snapshot " + vmSnapshot.getName() + " failed for vm: " + userVm.getInstanceName() + " due to " + msg);
            throw new CloudRuntimeException(msg);
        } finally{
            if(vmSnapshot.getState() == VMSnapshot.State.Allocated){
                s_logger.warn("Create vm snapshot " + vmSnapshot.getName() + " failed for vm: " + userVm.getInstanceName());
                _vmSnapshotDao.remove(vmSnapshot.getId());
            }
        }
    }

    private List<VolumeTO> getVolumeTOList(Long vmId) {
        List<VolumeTO> volumeTOs = new ArrayList<VolumeTO>();
        List<VolumeVO> volumeVos = _volumeDao.findByInstance(vmId);
        
        for (VolumeVO volume : volumeVos) {
            StoragePoolVO pool = _storagePoolDao.findById(volume.getPoolId());
            VolumeTO volumeTO = new VolumeTO(volume, pool);
            volumeTOs.add(volumeTO);
        }
        return volumeTOs;
    }

    // get snapshot and its parents recursively
    private VMSnapshotTO getSnapshotWithParents(VMSnapshotVO snapshot) {
        Map<Long, VMSnapshotVO> snapshotMap = new HashMap<Long, VMSnapshotVO>();
        List<VMSnapshotVO> allSnapshots = _vmSnapshotDao.findByVm(snapshot.getVmId());
        for (VMSnapshotVO vmSnapshotVO : allSnapshots) {
            snapshotMap.put(vmSnapshotVO.getId(), vmSnapshotVO);
        }

        VMSnapshotTO currentTO = convert2VMSnapshotTO(snapshot);
        VMSnapshotTO result = currentTO;
        VMSnapshotVO current = snapshot;
        while (current.getParent() != null) {
            VMSnapshotVO parent = snapshotMap.get(current.getParent());
            currentTO.setParent(convert2VMSnapshotTO(parent));
            current = snapshotMap.get(current.getParent());
            currentTO = currentTO.getParent();
        }
        return result;
    }

    private VMSnapshotTO convert2VMSnapshotTO(VMSnapshotVO vo) {
        return new VMSnapshotTO(vo.getId(), vo.getName(),  vo.getType(), vo.getCreated().getTime(), vo.getDescription(),
                vo.getCurrent(), null);
    }

    private boolean vmSnapshotStateTransitTo(VMSnapshotVO vsnp, VMSnapshot.Event event) throws NoTransitionException {
        return _vmSnapshottateMachine.transitTo(vsnp, event, null, _vmSnapshotDao);
    }
    
    @DB
    protected boolean transitState(VMSnapshotVO vsnp, VMSnapshot.Event e1, VMInstanceVO vm, VirtualMachine.Event e2, Long hostId){
        final Transaction txn = Transaction.currentTxn();
        try {
            txn.start();
            vmSnapshotStateTransitTo(vsnp, e1);
            vmStateTransitTo(vm, e2, hostId, null);
            return txn.commit();
        } catch (NoTransitionException e) {
            txn.rollback();
            return false;
        }finally{
            txn.close();
        }
    }
    
    @DB
    protected void processAnswer(VMSnapshotVO vmSnapshot, UserVmVO  userVm, Answer as, Long hostId) {
        final Transaction txn = Transaction.currentTxn();
        try {
            txn.start();
            if (as instanceof CreateVMSnapshotAnswer) {
                CreateVMSnapshotAnswer answer = (CreateVMSnapshotAnswer) as;
                finalizeCreate(vmSnapshot, answer.getVolumeTOs());
            } else if (as instanceof RevertToVMSnapshotAnswer) {
                RevertToVMSnapshotAnswer answer = (RevertToVMSnapshotAnswer) as;
                finalizeRevert(vmSnapshot, answer.getVolumeTOs());
            } else if (as instanceof DeleteVMSnapshotAnswer) {
                DeleteVMSnapshotAnswer answer = (DeleteVMSnapshotAnswer) as;
                finalizeDelete(vmSnapshot, answer.getVolumeTOs());
                _vmSnapshotDao.remove(vmSnapshot.getId());
            }
            txn.commit();
        } catch (Exception e) {
            String errMsg = "Error while process answer: " + as.getClass() + " due to " + e.getMessage();
            s_logger.error(errMsg, e);
            txn.rollback();
            throw new CloudRuntimeException(errMsg);
        } finally {
            txn.close();
        }
    }

    protected void finalizeDelete(VMSnapshotVO vmSnapshot, List<VolumeTO> VolumeTOs) {
        // update volumes path
        updateVolumePath(VolumeTOs);
        
        // update children's parent snapshots
        List<VMSnapshotVO> children= _vmSnapshotDao.listByParent(vmSnapshot.getId());
        for (VMSnapshotVO child : children) {
            child.setParent(vmSnapshot.getParent());
            _vmSnapshotDao.persist(child);
        }
        
        // update current snapshot 
        VMSnapshotVO current = _vmSnapshotDao.findCurrentSnapshotByVmId(vmSnapshot.getVmId());
        if(current != null && current.getId() == vmSnapshot.getId() && vmSnapshot.getParent() != null){
            VMSnapshotVO parent = _vmSnapshotDao.findById(vmSnapshot.getParent());
            parent.setCurrent(true);
            _vmSnapshotDao.persist(parent);
        }
        vmSnapshot.setCurrent(false);
        _vmSnapshotDao.persist(vmSnapshot);
    }

    protected void finalizeCreate(VMSnapshotVO vmSnapshot, List<VolumeTO> VolumeTOs) {
        // update volumes path
        updateVolumePath(VolumeTOs);
        
        vmSnapshot.setCurrent(true);
        
        // change current snapshot
        if (vmSnapshot.getParent() != null) {
            VMSnapshotVO previousCurrent = _vmSnapshotDao.findById(vmSnapshot.getParent());
            previousCurrent.setCurrent(false);
            _vmSnapshotDao.persist(previousCurrent);
        }
        _vmSnapshotDao.persist(vmSnapshot);
    }

    protected void finalizeRevert(VMSnapshotVO vmSnapshot, List<VolumeTO> volumeToList) {
        // update volumes path
        updateVolumePath(volumeToList);

        // update current snapshot, current snapshot is the one reverted to
        VMSnapshotVO previousCurrent = _vmSnapshotDao.findCurrentSnapshotByVmId(vmSnapshot.getVmId());
        previousCurrent.setCurrent(false);
        vmSnapshot.setCurrent(true);
        _vmSnapshotDao.persist(previousCurrent);
        _vmSnapshotDao.persist(vmSnapshot);
    }

    private void updateVolumePath(List<VolumeTO> volumeTOs) {
        for (VolumeTO volume : volumeTOs) {
            if (volume.getPath() != null) {
                VolumeVO volumeVO = _volumeDao.findById(volume.getId());
                volumeVO.setPath(volume.getPath());
                _volumeDao.persist(volumeVO);
            }
        }
    }
    
    protected VMSnapshotManagerImpl() {
        _vmSnapshottateMachine   = VMSnapshot.State.getStateMachine();
    }
    
    private Answer sendToPool(Long hostId, Command cmd) throws AgentUnavailableException, OperationTimedoutException {
        long targetHostId = _hvGuruMgr.getGuruProcessedCommandTargetHost(hostId, cmd);
        Answer answer = _agentMgr.send(targetHostId, cmd);
        return answer;
    }
    
    private boolean checkActiveVMSnapshotTasks(Long vmId){
        List<VMSnapshotVO> activeVMSnapshots = _vmSnapshotDao.listByInstanceId(vmId, 
                VMSnapshot.State.Creating, VMSnapshot.State.Expunging,VMSnapshot.State.Reverting,VMSnapshot.State.Allocated);
        return activeVMSnapshots.size() > 0;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_SNAPSHOT_DELETE, eventDescription = "delete_vm_snapshot", async=true)
    public boolean deleteVMSnapshot(DeleteVMSnapshotCmd cmd) {
        Long vmSnapshotId = cmd.getVmSnapShotId();
        Account caller = UserContext.current().getCaller();

        VMSnapshotVO vmSnapshot = _vmSnapshotDao.findById(vmSnapshotId);
        if (vmSnapshot == null) {
            throw new InvalidParameterValueException("unable to find the vm snapshot with id " + vmSnapshotId);
        }

        _accountMgr.checkAccess(caller, null, true, vmSnapshot);
        
        // check VM snapshot states, only allow to delete vm snapshots in created and error state
        if (VMSnapshot.State.Ready != vmSnapshot.getState() && VMSnapshot.State.Error != vmSnapshot.getState()) {
            throw new InvalidParameterValueException("Can't delete the vm snapshotshot " + vmSnapshotId + " due to it is not in Created or Error State");
        }
        
        // check if there are other active VM snapshot tasks
        if (checkActiveVMSnapshotTasks(vmSnapshot.getVmId())) {
            throw new InvalidParameterValueException("There is other active vm snapshot tasks on the instance, please try again later");
        }

        if(vmSnapshot.getState() == VMSnapshot.State.Allocated){
            return _vmSnapshotDao.remove(vmSnapshot.getId());
        }else{
            return deleteSnapshotInternal(vmSnapshot);
        }
    }

    @DB
    protected boolean deleteSnapshotInternal(VMSnapshotVO vmSnapshot) {
        UserVmVO userVm = _userVMDao.findById(vmSnapshot.getVmId());
        
        try {
            vmSnapshotStateTransitTo(vmSnapshot,VMSnapshot.Event.ExpungeRequested);
            Long hostId = pickRunningHost(vmSnapshot.getVmId());

            // prepare snapshotVolumeTos
            List<VolumeTO> volumeTOs = getVolumeTOList(vmSnapshot.getVmId());
            
            // prepare DeleteVMSnapshotCommand
            String vmInstanceName = userVm.getInstanceName();
            VMSnapshotTO parent = getSnapshotWithParents(vmSnapshot).getParent();
            VMSnapshotTO vmSnapshotTO = new VMSnapshotTO(vmSnapshot.getId(), vmSnapshot.getName(), vmSnapshot.getType(), 
                    vmSnapshot.getCreated().getTime(), vmSnapshot.getDescription(), vmSnapshot.getCurrent(), parent);
            GuestOSVO guestOS = _guestOsDao.findById(userVm.getGuestOSId());
            DeleteVMSnapshotCommand deleteSnapshotCommand = new DeleteVMSnapshotCommand(vmInstanceName, vmSnapshotTO, volumeTOs,guestOS.getDisplayName());
            
            DeleteVMSnapshotAnswer answer = (DeleteVMSnapshotAnswer) sendToPool(hostId, deleteSnapshotCommand);
           
            if (answer != null && answer.getResult()) {
                processAnswer(vmSnapshot, userVm, answer, hostId);
                return true;
            } else {
                s_logger.error("Delete vm snapshot " + vmSnapshot.getName() + " of vm " + userVm.getInstanceName() + " failed due to " + answer.getDetails());
                return false;
            }
        } catch (Exception e) {
            String msg = "Delete vm snapshot " + vmSnapshot.getName() + " of vm " + userVm.getInstanceName() + " failed due to " + e.getMessage();
            s_logger.error(msg , e);
            throw new CloudRuntimeException(e.getMessage());
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_SNAPSHOT_REVERT, eventDescription = "revert_vm", async = true)
    public UserVm revertToSnapshot(RevertToSnapshotCmd cmd) throws InsufficientCapacityException, ResourceUnavailableException, ConcurrentOperationException {
        Long vmId = cmd.getVmId();
        Long vmSnapshotId = cmd.getVmSnapShotId();
        UserVmVO userVm = _userVMDao.findById(vmId);

        Account caller = UserContext.current().getCaller();

        // check if VM exists
        if (userVm == null) {
            throw new InvalidParameterValueException("Revert vm to snapshot: "
                    + vmSnapshotId + " failed due to vm: " + vmId
                    + " is not found");
        }
        
        // check if there are other active VM snapshot tasks
        if (checkActiveVMSnapshotTasks(vmId)) {
            throw new InvalidParameterValueException("There is other active vm snapshot tasks on the instance, please try again later");
        }
        
        // check if VM snapshot exists in DB
        VMSnapshotVO vmSnapshotVo = _vmSnapshotDao.findById(vmSnapshotId);
        
        _accountMgr.checkAccess(caller, null, true, vmSnapshotVo);
        
        if (vmSnapshotVo == null) {
            throw new InvalidParameterValueException(
                    "unable to find the vm snapshot with id " + vmSnapshotId);
        }

        // VM should be in running or stopped states
        if (userVm.getState() != VirtualMachine.State.Running
                && userVm.getState() != VirtualMachine.State.Stopped) {
            throw new InvalidParameterValueException(
                    "VM Snapshot reverting failed due to vm is not in the state of Running or Stopped.");
        }
        
        // if snapshot is not created, error out
        if (vmSnapshotVo.getState() != VMSnapshot.State.Ready) {
            throw new InvalidParameterValueException(
                    "VM Snapshot reverting failed due to vm snapshot is not in the state of Created.");
        }

        UserVO callerUser = _userDao.findById(UserContext.current().getCallerUserId());
        
        UserVmVO vm = null;
        Long hostId = null;
        Account owner = _accountDao.findById(vmSnapshotVo.getAccountId());
        
        // start or stop VM if revert from stopped to running, or from running to stopped
        if(userVm.getState() == VirtualMachine.State.Stopped && vmSnapshotVo.getType() == Type.DiskAndMemory){
            try {
        	    vm = _itMgr.advanceStart(userVm, new HashMap<VirtualMachineProfile.Param, Object>(), callerUser, owner);
        	    hostId = vm.getHostId();
        	} catch (Exception e) {
        	    s_logger.error("Start VM " + userVm.getInstanceName() + " before reverting failed due to " + e.getMessage());
        	    throw new CloudRuntimeException(e.getMessage());
        	}
        }else {
            if(userVm.getState() == VirtualMachine.State.Running && vmSnapshotVo.getType() == Type.Disk){
                try {
    			    _itMgr.advanceStop(userVm, true, callerUser, owner);
                } catch (Exception e) {
                    s_logger.error("Stop VM " + userVm.getInstanceName() + " before reverting failed due to " + e.getMessage());
    			    throw new CloudRuntimeException(e.getMessage());
                }
            }
            hostId = pickRunningHost(userVm.getId());
        }
        
        if(hostId == null)
            throw new CloudRuntimeException("Can not find any host to revert snapshot " + vmSnapshotVo.getName());
        
        // check if there are other active VM snapshot tasks
        if (checkActiveVMSnapshotTasks(userVm.getId())) {
            throw new InvalidParameterValueException("There is other active vm snapshot tasks on the instance, please try again later");
        }
        
        userVm = _userVMDao.findById(userVm.getId());
        transitState(vmSnapshotVo, VMSnapshot.Event.RevertRequested, userVm, VirtualMachine.Event.RevertRequested, hostId);
        return revertInternal(userVm, vmSnapshotVo, hostId);
    }

    private UserVm revertInternal(UserVmVO userVm, VMSnapshotVO vmSnapshotVo, Long hostId) {

        try {
            
            VMSnapshotVO snapshot = _vmSnapshotDao.findById(vmSnapshotVo.getId());
            // prepare RevertToSnapshotCommand
            List<VolumeTO> volumeTOs = getVolumeTOList(userVm.getId());
            String vmInstanceName = userVm.getInstanceName();
            VMSnapshotTO parent = getSnapshotWithParents(snapshot).getParent();
            VMSnapshotTO vmSnapshotTO = new VMSnapshotTO(snapshot.getId(), snapshot.getName(), snapshot.getType(), 
            		snapshot.getCreated().getTime(), snapshot.getDescription(), snapshot.getCurrent(), parent);
            
            GuestOSVO guestOS = _guestOsDao.findById(userVm.getGuestOSId());
            RevertToVMSnapshotCommand revertToSnapshotCommand = new RevertToVMSnapshotCommand(vmInstanceName, vmSnapshotTO, volumeTOs, guestOS.getDisplayName());
           
            RevertToVMSnapshotAnswer answer = (RevertToVMSnapshotAnswer) sendToPool(hostId, revertToSnapshotCommand);
            if (answer.getResult()) {
                processAnswer(vmSnapshotVo, userVm, answer, hostId);
                transitState(vmSnapshotVo, VMSnapshot.Event.OperationSucceeded, userVm, VirtualMachine.Event.OperationSucceeded, hostId);
            } else {
                String errMsg = "Revert VM: " + userVm.getInstanceName() + " to snapshot: "+ vmSnapshotVo.getName() + " failed";
                if(answer != null && answer.getDetails() != null)
                    errMsg = errMsg + " due to " + answer.getDetails();
                s_logger.error(errMsg);
                // agent report revert operation fails
                transitState(vmSnapshotVo, VMSnapshot.Event.OperationFailed, userVm, VirtualMachine.Event.OperationFailed, hostId);
                throw new CloudRuntimeException(errMsg);
            }
        } catch (Exception e) {
            // for other exceptions, do not change VM state, leave it for snapshotSync
            String errMsg = "revert vm: " + userVm.getInstanceName() + " to snapshot " + vmSnapshotVo.getName() + " failed due to " + e.getMessage();
            s_logger.error(errMsg);
            throw new CloudRuntimeException(e.getMessage());
        } 
        return userVm;
    }

    private boolean vmStateTransitTo(VMInstanceVO vm, VirtualMachine.Event e, Long hostId, String reservationId) throws NoTransitionException {
        vm.setReservationId(reservationId);
        return _stateMachine.transitTo(vm, e, new Pair<Long, Long>(vm.getHostId(), hostId), _vmInstanceDao);
    }

    @Override
    public VMSnapshot getVMSnapshotById(long id) {
        VMSnapshotVO vmSnapshot = _vmSnapshotDao.findById(id);
        return vmSnapshot;
    }

    private Long pickRunningHost(Long vmId) {
        UserVmVO vm = _userVMDao.findById(vmId);
        // use VM's host if VM is running
        if(vm.getState() == State.Running)
            return vm.getHostId();
        
        // check if lastHostId is available
        if(vm.getLastHostId() != null){
           HostVO lastHost =  _hostDao.findById(vm.getLastHostId());
           if(lastHost.getStatus() == com.cloud.host.Status.Up && !lastHost.isInMaintenanceStates())
               return lastHost.getId();
        }
        
        List<VolumeVO> listVolumes = _volumeDao.findByInstance(vmId);
        if (listVolumes == null || listVolumes.size() == 0) {
            throw new InvalidParameterValueException("vmInstance has no volumes");
        }
        VolumeVO volume = listVolumes.get(0);
        Long poolId = volume.getPoolId();
        if (poolId == null) {
            throw new InvalidParameterValueException("pool id is not found");
        }
        StoragePoolVO storagePool = _storagePoolDao.findById(poolId);
        if (storagePool == null) {
            throw new InvalidParameterValueException("storage pool is not found");
        }
        List<HostVO> listHost = _hostDao.listAllUpAndEnabledNonHAHosts(Host.Type.Routing, storagePool.getClusterId(), storagePool.getPodId(),
                storagePool.getDataCenterId(), null);
        if (listHost == null || listHost.size() == 0) {
            throw new InvalidParameterValueException("no host in up state is found");
        }
        return listHost.get(0).getId();
    }

    @Override
    public VirtualMachine getVMBySnapshotId(Long id) {
        VMSnapshotVO vmSnapshot = _vmSnapshotDao.findById(id);
        Long vmId = vmSnapshot.getVmId();
        UserVmVO vm = _vmDao.findById(vmId);
        return vm;
    }

    @Override
    public boolean deleteAllVMSnapshots(long vmId) {
        boolean result = true;
        List<VMSnapshotVO> listVmSnapshots = _vmSnapshotDao.findByVm(vmId);
        if (listVmSnapshots == null || listVmSnapshots.isEmpty()) {
            return true;
        }
        for (VMSnapshotVO snapshot : listVmSnapshots) {
            VMSnapshotVO snapshotVo = _vmSnapshotDao.findById(snapshot.getId());
            if (!deleteSnapshotInternal(snapshotVo)) {
                result = false;
                break;
            }
        }
        return result;
    }

    @Override
    public boolean syncVMSnapshot(VMInstanceVO vm, VMSnapshotVO vmSnapshot, Long hostId) {
        try{
            
            UserVmVO userVm = _userVMDao.findById(vm.getId());
            if(userVm == null)
                return false;
            if(userVm.getState() == State.RevertingToRunning || userVm.getState() == State.RevertingToStopped){
                List<VMSnapshotVO> vmSnapshots = _vmSnapshotDao.listByInstanceId(userVm.getId(), VMSnapshot.State.Reverting);
                if(vmSnapshots.size() != 1){
                    s_logger.error("VM:" + userVm.getInstanceName()+ " expected to have one VM snapshot in reverting state but actually has " + vmSnapshots.size());
                    return false;
                }
                // send revert command again
                revertInternal(userVm, vmSnapshots.get(0), hostId);
                return true;
            }else if (userVm.getState() == State.RunningSnapshotting || userVm.getState() == State.StoppedSnapshotting){
                List<VMSnapshotVO> vmSnapshots = _vmSnapshotDao.listByInstanceId(userVm.getId(), VMSnapshot.State.Creating);
                if(vmSnapshots.size() != 1){
                    s_logger.error("VM:" + userVm.getInstanceName()+ " expected to have one VM snapshot in creating state but actually has " + vmSnapshots.size());
                    return false;
                }
                // send create vm snapshot command again
                createVmSnapshotInternal(userVm, vmSnapshots.get(0), hostId);
                return true;
                
            }else if (vmSnapshot != null && vmSnapshot.getState() == VMSnapshot.State.Expunging){
                return deleteSnapshotInternal(vmSnapshot);
            }
        }catch (Exception e) {
            s_logger.error(e.getMessage(),e);
            return false;
        }
        return false;
    }
 
}
