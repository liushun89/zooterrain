/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.brainlounge.zooterrain.zkclient;

import com.google.common.collect.Sets;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 */
public class ZkStateObserver implements Watcher {

    private static final Logger logger = Logger.getLogger(ZkStateObserver.class.getName());

    private String zkConnection;
    protected ZooKeeper zk;
    protected Set<ZkStateListener> listeners = new HashSet<ZkStateListener>();
    protected final Map<String, ZNodeState> znodeStateCache = new HashMap<String, ZNodeState>();

    protected AtomicReference<String> status = new AtomicReference<String>("unknown");

    public ZkStateObserver(String zkConnection) {
        this.zkConnection = zkConnection;
    }

    public String getZkConnection() {
        return zkConnection;
    }

    public ZooKeeper getZk() {
        return zk;
    }

    public String getStatus() {
        return status.get();
    }

    public void addListener(ZkStateListener listener) {
        listeners.add(listener);
    }

    public boolean removeListener(ZkStateListener listener) {
        return listeners.remove(listener);
    }

    public void start() {
        try {
            logger.info("trying reaching ZK at " + zkConnection);
            zk = new ZooKeeper(zkConnection, 30 * 1000, null, false);
        } catch (IOException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            throw new RuntimeException("zk connection failed");
        }
        zk.register(this);
        try {
            zk.getChildren("/", this);
        } catch (KeeperException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (InterruptedException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
    }

    public void stop() {
        ZooKeeper zkLocal = zk;
        zk = null;
        if (zkLocal != null) {
            try {
                zkLocal.close();
            } catch (Exception e) {
                ;
            }
        }
    }

    public void initialTree(String subtree, int depth, Set<ZkStateListener> receivers) throws InterruptedException {
        if (depth <= 0) return;
        if (subtree == null) subtree = "/";

        ZNodeState zNodeState = retrieveOrCreateZNodeState(subtree);
        zNodeState.acquire();
        Set<String> currentChildNames = new HashSet<String>();
        try {
            final List<String> children = zk.getChildren(subtree, true);

            for (String child : children) {
                currentChildNames.add(child);
            }
            zNodeState.setChildren(currentChildNames);
        } catch (KeeperException e) {
            // znode has gone away
            znodeStateCache.remove(subtree);
        } finally {
            zNodeState.release();
        }
        
        for (String child : currentChildNames) {
            final String childFQPath = subtree.equals("/") ? ("/" + child) : (subtree + "/" + child);
            Stat childStat;
            try {
                childStat = zk.exists(childFQPath, true);
            } catch (KeeperException e) {
                e.printStackTrace();
                continue;
            }
            propagateToListeners(new ZNodeMessage(childFQPath, ZNodeMessage.Type.U, childStat), receivers);
            initialTree(childFQPath, depth - 1, receivers);
        }
    }

    public DataMessage retrieveNodeData(String znode) {
        Stat stat = new Stat();
        try {
            byte[] data = zk.getData(znode, true, stat);
            if (data != null && data.length > 500) {
                byte[] dataShortend = new byte[500];
                System.arraycopy(data, 0, dataShortend, 0, 500);
                data = dataShortend;
            }
            return new DataMessage(znode, data, stat);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    
    @Override
    public void process(WatchedEvent event) {
        try {
            final String eventPath = event.getPath();
            switch (event.getType()) {
    
                case None:
                    final Event.KeeperState state = event.getState();
                    logger.info("new ZK connection state: " + state.toString());

                    if (state == Event.KeeperState.Expired) {
                        logger.info("trying to reestablished expired connection");
                        stop();
                        start();
                        return;
                    }
                    
                    status.set(state.toString());
                    propagateToListeners(new ControlMessage(state.toString(), ControlMessage.Type.Z));
                    break;
                case NodeCreated:
                    //propagateToListeners(new ZNodeMessage(eventPath, ZNodeMessage.Type.C));
                    break;
                case NodeDeleted:
                    //propagateToListeners(new ZNodeMessage(eventPath, ZNodeMessage.Type.D));
                    break;
                case NodeDataChanged:
                    handleDataChanged(eventPath);
                    
                case NodeChildrenChanged:
                    handleChildrenChanged(eventPath);
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
    }

    private void handleDataChanged(String eventPath) {
        Stat stat = null;
        try {
            stat = zk.exists(eventPath, true);
        } catch (KeeperException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (InterruptedException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        propagateToListeners(new ZNodeMessage(eventPath, ZNodeMessage.Type.U, stat));
    }

    private void handleChildrenChanged(String eventPath) throws InterruptedException, KeeperException {
        ZNodeState zNodeState = retrieveOrCreateZNodeState(eventPath);
        zNodeState.acquire();
        try {
            final Set<String> lastTimeChildren = zNodeState.getChildren();
            Set<String> newChildNames = new HashSet<String>();

            final List<String> children;
            try {
                children = zk.getChildren(eventPath, true);
            } catch (KeeperException e) {
                znodeStateCache.remove(eventPath);
                return;
            }

            for (String child : children) {
                newChildNames.add(child);
            }
            zNodeState.setChildren(newChildNames);

            final Sets.SetView<String> removedChilds = Sets.difference(lastTimeChildren, newChildNames);
            final Sets.SetView<String> newChilds = Sets.difference(newChildNames, lastTimeChildren);

            for (String removedChild : removedChilds) {
                final String childFQPath = eventPath.equals("/") ? ("/" + removedChild) : (eventPath + "/" + removedChild);
                propagateToListeners(new ZNodeMessage(childFQPath, ZNodeMessage.Type.D, null));
            }

            for (String newChild : newChilds) {
                final String childFQPath = eventPath.equals("/") ? ("/" + newChild) : (eventPath + "/" + newChild);

                Stat childStat;
                try {
                    childStat = zk.exists(childFQPath, true);
                } catch (KeeperException e) {
                    continue;
                }
                
                propagateToListeners(new ZNodeMessage(childFQPath, ZNodeMessage.Type.C, childStat));
                zk.getChildren(childFQPath, true);
            }
        } finally {
            zNodeState.release();
        }
    }

    private ZNodeState retrieveOrCreateZNodeState(String path) {
        ZNodeState zNodeState = znodeStateCache.get(path);
        if (zNodeState == null) {
            synchronized (znodeStateCache) {
                ZNodeState zNodeStateAgain = znodeStateCache.get(path);
                if (zNodeStateAgain == null) {
                    zNodeState = new ZNodeState();
                    znodeStateCache.put(path, zNodeState);
                }
            }
        }
        return zNodeState;
    }

    private void propagateToListeners(ClientMessage message) {
        propagateToListeners(message, listeners);
    }
    
    private void propagateToListeners(ClientMessage message, Set<ZkStateListener> stateListeners) {
        for (ZkStateListener listener : stateListeners) {
            listener.zkNodeEvent(message);
        }
    }
}
