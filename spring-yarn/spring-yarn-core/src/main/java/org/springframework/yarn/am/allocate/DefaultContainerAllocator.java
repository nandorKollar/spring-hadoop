/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.yarn.am.allocate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateRequest;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.util.Records;
import org.springframework.util.StringUtils;
import org.springframework.yarn.am.allocate.DefaultAllocateCountTracker.AllocateCountInfo;
import org.springframework.yarn.listener.CompositeContainerAllocatorListener;
import org.springframework.yarn.listener.ContainerAllocatorListener;
import org.springframework.yarn.support.compat.ResourceCompat;

/**
 * Default allocator which polls resource manager, requests new containers
 * and acts as a heart beat sender at the same time.
 *
 * @author Janne Valkealahti
 *
 */
public class DefaultContainerAllocator extends AbstractPollingAllocator implements ContainerAllocator {

    private static final Log log = LogFactory.getLog(DefaultContainerAllocator.class);

    /** Listener dispatcher for events */
    private CompositeContainerAllocatorListener allocatorListener = new CompositeContainerAllocatorListener();

    /** Container request priority */
    private int priority = 0;

    /** Resource capability as of cores */
    private int virtualcores = 1;

    /** Resource capability as of memory */
    private int memory = 64;

    /** Locality relaxing */
    private boolean locality = false;

    private Map<Integer, ContainerAllocationValues> allocationParams = new ConcurrentHashMap<Integer, ContainerAllocationValues>();

    private Map<Integer, DefaultAllocateCountTracker> allocateCountTrackers = new ConcurrentHashMap<Integer, DefaultAllocateCountTracker>();

    private Map<String, Integer> idToPriority = new ConcurrentHashMap<String, Integer>();

    /** Increasing counter for rpc request id*/
    private AtomicInteger requestId = new AtomicInteger();

    /** Current progress reported during allocate requests */
    private float applicationProgress = 0;

    /** Queued container id's to be released */
    private Queue<ContainerId> releaseContainers = new ConcurrentLinkedQueue<ContainerId>();

    /** Internal set of containers marked as garbage by allocate tracker */
    private Set<ContainerId> garbageContainers = new HashSet<ContainerId>();

    /** Flag helping to avoid allocation garbage */
    private AtomicBoolean allocationDirty = new AtomicBoolean();

    /** Empty list for requests without container asks */
    private final List<ResourceRequest> EMPTY = new ArrayList<ResourceRequest>();

    @Override
    protected void onInit() throws Exception {
        super.onInit();
        internalInit();
    }

    @Override
    public void allocateContainers(int count) {
        if (log.isDebugEnabled()) {
            log.debug("Incoming count: " + count);
        }
        DefaultAllocateCountTracker tracker = allocateCountTrackers.get(priority);
        tracker.addContainers(count);
        allocationDirty.set(true);
    }

    @Override
    public void addListener(ContainerAllocatorListener listener) {
        allocatorListener.register(listener);
    }

    @Override
    public void allocateContainers(ContainerAllocateData containerAllocateData) {
        log.info("Incoming containerAllocateData: " + containerAllocateData);
        DefaultAllocateCountTracker tracker = getAllocateCountTracker(containerAllocateData.getId());
        log.info("State allocateCountTracker before adding allocation data: " + tracker);
        tracker.addContainers(containerAllocateData);
        allocationDirty.set(true);
        log.info("State allocateCountTracker after adding allocation data: " + tracker);
    }

    @Override
    public void releaseContainers(List<Container> containers) {
        for (Container container : containers) {
            releaseContainer(container.getId());
        }
    }

    @Override
    public void releaseContainer(ContainerId containerId) {
        log.info("Adding new container to be released containerId=" + containerId);
        releaseContainers.add(containerId);
    }

    public void setAllocationValues(String id, Integer priority, Integer virtualcores, Integer memory, Boolean locality) {

    	if (log.isTraceEnabled()) {
        	log.trace("setAllocationValues 1: id=" + id + " priority=" + priority + " cores=" + virtualcores + " memory=" + memory + " locality=" + locality);
    	}

    	id = StringUtils.hasText(id) ? id : "";
    	if (!idToPriority.containsKey(id) && idToPriority.containsValue(priority)) {
    		throw new IllegalArgumentException("Key " + id + " is already mapped to priority " + priority);
    	} else {
    		idToPriority.put(id, priority);
    	}
    	allocationParams.put(priority, new ContainerAllocationValues(priority, virtualcores, memory, locality));
    	allocateCountTrackers.put(priority, new DefaultAllocateCountTracker(id, getConfiguration()));
    }

    private ContainerAllocationValues getAllocationValues(String id) {
    	return allocationParams.get(idToPriority.get(id));
    }

    private DefaultAllocateCountTracker getAllocateCountTracker(String id) {
    	id = StringUtils.hasText(id) ? id : "";
    	return allocateCountTrackers.get(idToPriority.containsKey(id) ? idToPriority.get(id) : idToPriority.get(""));
    }

    private List<ResourceRequest> createRequests() {
        List<ResourceRequest> requestedContainers = new ArrayList<ResourceRequest>();

        for (DefaultAllocateCountTracker tracker : allocateCountTrackers.values()) {
        	AllocateCountInfo allocateCounts = tracker.getAllocateCounts();
        	ContainerAllocationValues allocationValues = getAllocationValues(tracker.getId());
        	if (log.isTraceEnabled()) {
    			log.trace("trace 1 " + allocationValues.locality);
    			log.trace("trace 2 tracker id:" + tracker.getId());
        	}
        	boolean hostsAdded = false;
        	for (Entry<String, Integer> entry : allocateCounts.hostsInfo.entrySet()) {
            	if (log.isTraceEnabled()) {
        			log.trace("trace 3 entry key=" + entry.getKey() + " value=" + entry.getValue());
            	}
        		requestedContainers.add(getContainerResourceRequest(tracker.getId(), entry.getValue(), entry.getKey(), true));
        		hostsAdded = true;
        	}
        	for (Entry<String, Integer> entry : allocateCounts.racksInfo.entrySet()) {
            	if (log.isTraceEnabled()) {
        			log.trace("trace 4 entry key=" + entry.getKey() + " value=" + entry.getValue());
            	}
        		requestedContainers.add(getContainerResourceRequest(tracker.getId(), entry.getValue(), entry.getKey(), (hostsAdded && allocationValues.locality) ? false : true));
        	}
        	for (Entry<String, Integer> entry : allocateCounts.anysInfo.entrySet()) {
            	if (log.isTraceEnabled()) {
        			log.trace("trace 5 entry key=" + entry.getKey() + " value=" + entry.getValue());
            	}
        		requestedContainers.add(getContainerResourceRequest(tracker.getId(), entry.getValue(), entry.getKey(), !allocationValues.locality));
        	}

        }

        return requestedContainers;
    }

    @Override
    protected AllocateResponse doContainerRequest() {
    	List<ResourceRequest> requestedContainers = null;

    	if (allocationDirty.getAndSet(false)) {
    		requestedContainers = createRequests();
    	} else {
    		requestedContainers = EMPTY;
    	}

        // add pending containers to be released
        List<ContainerId> release = new ArrayList<ContainerId>();
        ContainerId element = null;
        while ((element = releaseContainers.poll()) != null) {
            release.add(element);
        }

        if (log.isDebugEnabled()) {
            log.debug("Requesting containers using " + requestedContainers.size() + " requests.");
            for (ResourceRequest resourceRequest : requestedContainers) {
                log.debug("ResourceRequest: " + resourceRequest + " with count=" +
                        resourceRequest.getNumContainers() + " with hostName=" + resourceRequest.getResourceName());
            }
            log.debug("Releasing containers " + release.size());
            for (ContainerId cid : release) {
                log.debug("Release container=" + cid);
            }
            log.debug("Request id will be: " + requestId.get());
        }

        // build the allocation request
        AllocateRequest request = Records.newRecord(AllocateRequest.class);
        request.setResponseId(requestId.get());
        request.setAskList(requestedContainers);
        request.setReleaseList(release);
        request.setProgress(applicationProgress);

        // do request and return response
        AllocateResponse allocate = getRmTemplate().allocate(request);
        requestId.set(allocate.getResponseId());
        return allocate;
    }

    @Override
    protected List<Container> preProcessAllocatedContainers(List<Container> containers) {
        // checking if we have standing allocation counts,
        // if not assume as garbage and send to release queue.
        // what's left will be out of our hand and expected to be
        // processed by the listener and eventually send back
        // to us as a released container.

		List<Container> preProcessed = new ArrayList<Container>();
		for (Container container : containers) {

			DefaultAllocateCountTracker tracker = allocateCountTrackers.get(container.getPriority().getPriority());

			if (log.isDebugEnabled()) {
				log.debug("State allocateCountTracker before handling allocated container: " + tracker);
			}
			Container processed = allocateCountTrackers.get(container.getPriority().getPriority())
					.processAllocatedContainer(container);
			if (processed != null) {
				preProcessed.add(processed);
			} else {
				garbageContainers.add(container.getId());
				releaseContainers.add(container.getId());
			}
			if (log.isDebugEnabled()) {
				log.debug("State allocateCountTracker after handling allocated container: " + tracker);
			}
		}
		return preProcessed;
    }

    @Override
    protected void handleAllocatedContainers(List<Container> containers) {
        allocatorListener.allocated(containers);
    }

    @Override
    protected void handleCompletedContainers(List<ContainerStatus> containerStatuses) {
        // strip away containers which were already marked
        // garbage by allocate tracker. system
        // never knew those even exist and might create mess
        // with monitor component. monitor only sees
        // complete status which is also the case for garbage
        // when it's released.
        List<ContainerStatus> garbageFree = new ArrayList<ContainerStatus>();
        for (ContainerStatus status : containerStatuses) {
            if (!garbageContainers.contains(status.getContainerId())) {
                garbageFree.add(status);
            }
        }
        allocatorListener.completed(garbageFree);
    }

    @Override
    public void setProgress(float progress) {
        applicationProgress = progress;
    }

    /**
     * Gets the priority for container request.
     *
     * @return the priority
     */
    public int getPriority() {
        return priority;
    }

    /**
     * Sets the priority for container request.
     *
     * @param priority the new priority
     */
    public void setPriority(int priority) {
        this.priority = priority;
    }

    /**
     * Gets the virtualcores for container request.
     *
     * @return the virtualcores
     */
    public int getVirtualcores() {
        return virtualcores;
    }

    /**
     * Sets the virtualcores for container request defining
     * <em>number of virtual cpu cores</em> of the resource.
     *
     * @param virtualcores the new virtualcores
     */
    public void setVirtualcores(int virtualcores) {
        this.virtualcores = virtualcores;
    }

    /**
     * Gets the memory for container request.
     *
     * @return the memory
     */
    public int getMemory() {
        return memory;
    }

    /**
     * Sets the memory for container request defining
     * <em>memory</em> of the resource.
     *
     * @param memory the new memory
     */
    public void setMemory(int memory) {
        this.memory = memory;
    }

    /**
     * Checks if is locality relax flag is enabled.
     *
     * @return true, if is locality is enabled.
     */
    public boolean isLocality() {
        return locality;
    }

    /**
     * Sets the flag telling if resource allocation
     * should not be relaxed. Setting this to true
     * means that relaxing is not used. Default value
     * is false.
     *
     * @param locality the new locality relax flag
     */
    public void setLocality(boolean locality) {
        this.locality = locality;
    }

    /**
     * Internal init which should only configure this class and
     * should not touch parent classes.
     */
    private void internalInit() {
    	setAllocationValues(null, priority, virtualcores, memory, locality);
    	for (DefaultAllocateCountTracker tracker : allocateCountTrackers.values()) {
    		tracker.setConfiguration(getConfiguration());
    	}
    }

    /**
     * Utility method creating a {@link ResourceRequest}.
     *
     * @param numContainers number of containers to request
     * @return request to be sent to resource manager
     */
    private ResourceRequest getContainerResourceRequest(String id, int numContainers, String hostName, boolean relaxLocality) {

    	ContainerAllocationValues allocationValues = getAllocationValues(id);

        ResourceRequest request = Records.newRecord(ResourceRequest.class);
        request.setRelaxLocality(relaxLocality);
        request.setResourceName(hostName);
        request.setNumContainers(numContainers);
        Priority pri = Records.newRecord(Priority.class);
        pri.setPriority(allocationValues.priority);
        request.setPriority(pri);
        Resource capability = Records.newRecord(Resource.class);
        capability.setMemory(allocationValues.memory);
        ResourceCompat.setVirtualCores(capability, allocationValues.virtualcores);
        request.setCapability(capability);
        return request;
    }

    private static class ContainerAllocationValues {
        int priority = 0;
        int virtualcores = 1;
        int memory = 64;
        boolean locality = false;
		public ContainerAllocationValues(Integer priority, Integer virtualcores, Integer memory, Boolean locality) {
			if (priority != null) {
				this.priority = priority;
			}
			if (virtualcores != null) {
				this.virtualcores = virtualcores;
			}
			if (memory != null) {
				this.memory = memory;
			}
			if (locality != null) {
				this.locality = locality;
			}
		}
		@Override
		public String toString() {
			return "ContainerAllocationValues [priority=" + priority + ", virtualcores=" + virtualcores + ", memory="
					+ memory + ", locality=" + locality + "]";
		}

    }

}