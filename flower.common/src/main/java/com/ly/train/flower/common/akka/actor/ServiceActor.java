/**
 * Copyright © 2019 同程艺龙 (zhihui.li@ly.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ly.train.flower.common.akka.actor;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import com.ly.train.flower.common.akka.ServiceFacade;
import com.ly.train.flower.common.akka.ServiceRouter;
import com.ly.train.flower.common.exception.FlowerException;
import com.ly.train.flower.common.service.Aggregate;
import com.ly.train.flower.common.service.Complete;
import com.ly.train.flower.common.service.FlowerService;
import com.ly.train.flower.common.service.Service;
import com.ly.train.flower.common.service.config.ServiceConfig;
import com.ly.train.flower.common.service.container.ServiceContext;
import com.ly.train.flower.common.service.container.ServiceFactory;
import com.ly.train.flower.common.service.container.ServiceFlow;
import com.ly.train.flower.common.service.container.ServiceLoader;
import com.ly.train.flower.common.service.impl.AggregateService;
import com.ly.train.flower.common.service.message.Condition;
import com.ly.train.flower.common.service.message.FlowMessage;
import com.ly.train.flower.common.service.web.Flush;
import com.ly.train.flower.common.service.web.HttpComplete;
import com.ly.train.flower.common.service.web.Web;
import com.ly.train.flower.common.util.CloneUtil;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.dispatch.Futures;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

/**
 * Wrap service by actor, make service driven by message.
 * 
 * @author zhihui.li
 *
 */
public class ServiceActor extends AbstractFlowerActor {
  /**
   * 同步要求结果的actor
   */
  private static final Map<String, ActorRef> syncActors = new ConcurrentHashMap<String, ActorRef>();

  protected final Future<String> delayFuture = Futures.successful("delay");
  protected final FiniteDuration maxTimeout = Duration.create(9999, TimeUnit.DAYS);

  private FlowerService service;
  private int count;

  static public Props props(String serviceName, int count) {
    return Props.create(ServiceActor.class, serviceName, count);
  }

  /**
   * 当前Actor绑定的服务
   */
  private String serviceName;

  public ServiceActor(String serviceName, int count) {
    this.serviceName = serviceName;
    this.count = count;
  }

  public void onServiceContextReceived(ServiceContext serviceContext) throws Throwable {
    FlowMessage fm = serviceContext.getFlowMessage();
    if (needCacheActorRef(serviceContext)) {
      syncActors.putIfAbsent(serviceContext.getId(), getSender());
    }

    Object result = null;
    try {
      result = ((Service) getService(serviceContext)).process(fm.getMessage(), serviceContext);
    } catch (Throwable e) {
      Web web = serviceContext.getWeb();
      if (web != null) {
        web.complete();
      }
      throw new FlowerException(
          "fail to invoke service " + serviceContext.getCurrentServiceName() + " : " + service + ", param : " + fm.getMessage(), e);
    }

    // logger.info("同步处理 ： {}, hasChild : {}", serviceContext.isSync(), hasChildActor());
    Set<RefType> nextActorRef = getNextServiceActors(serviceContext);
    if (serviceContext.isSync() && nextActorRef.isEmpty()) {
      ActorRef actor = syncActors.get(serviceContext.getId());
      if (actor != null) {
        actor.tell(result, getSelf());
        syncActors.remove(serviceContext.getId());
      }
      return;
    }

    Web web = serviceContext.getWeb();
    if (web != null) {
      if (service instanceof Flush) {
        web.flush();
      }
      if (service instanceof HttpComplete || service instanceof Complete) {
        web.complete();
      }
    }

    if (result == null) {// for joint service
      return;
    }

    for (RefType refType : getNextServiceActors(serviceContext)) {
      Object resultClone = CloneUtil.clone(result);
      ServiceContext context = serviceContext.newInstance();
      context.getFlowMessage().setMessage(resultClone);

      // condition fork for one-service to multi-service
      if (refType.getMessageType().isInstance(result)) {
        if (!(result instanceof Condition) || !(((Condition) result).getCondition() instanceof String)
            || stringInStrings(refType.getServiceName(), ((Condition) result).getCondition().toString())) {
          // refType.getActor().tell(context, getSelf());
          context.setCurrentServiceName(refType.getServiceName());
          refType.getServiceRouter().asyncCallService(context);
        }
      }
    }

  }

  /**
   * 懒加载方式获取服务实例
   * 
   * @return {@link FlowerService}
   */
  public FlowerService getService(ServiceContext serviceContext) {
    if (this.service == null) {
      this.service = ServiceFactory.getService(serviceName);
      if (service instanceof Aggregate) {
        ((AggregateService) service)
            .setSourceNumber(ServiceFlow.getOrCreate(serviceContext.getFlowName()).getServiceConfig(serviceName).getJointSourceNumber());
      }
    }
    return service;
  }

  private static final ConcurrentMap<String, Set<RefType>> nextServiceActorCache = new ConcurrentHashMap<>();

  private Set<RefType> getNextServiceActors(ServiceContext serviceContext) {
    final String cacheKey = serviceContext.getFlowName() + "_" + serviceContext.getCurrentServiceName();
    Set<RefType> nextServiceActors = nextServiceActorCache.get(cacheKey);
    if (nextServiceActors == null) {
      nextServiceActors = new HashSet<>();
      Set<ServiceConfig> serviceConfigs =
          ServiceFlow.getOrCreate(serviceContext.getFlowName()).getNextFlow(serviceContext.getCurrentServiceName());
      if (serviceConfigs != null) {
        for (ServiceConfig serviceConfig : serviceConfigs) {
          RefType refType = new RefType();

          refType.setAggregate(serviceConfig.isAggregateService());
          refType.setServiceRouter(ServiceFacade.buildServiceRouter(serviceConfig.getServiceName(), count));
          refType.setMessageType(ServiceLoader.getInstance().loadServiceMeta(serviceConfig.getServiceName()).getParamType());
          refType.setServiceName(serviceConfig.getServiceName());
          nextServiceActors.add(refType);
          nextServiceActorCache.put(cacheKey, nextServiceActors);
        }
      }
    }

    return nextServiceActors;
  }


  private boolean needCacheActorRef(ServiceContext serviceContext) {
    return serviceContext.isSync() && !syncActors.containsKey(serviceContext.getId());
  }

  /**
   * Is String s in String ss?
   * 
   * @param s "service1"
   * @param ss “service1,service2”
   * @return
   */
  private boolean stringInStrings(String s, String ss) {
    String[] sa = ss.split(",");
    if (sa != null && sa.length > 0) {
      for (String se : sa) {
        if (se.equals(s)) {
          return true;
        }
      }
    }
    return false;
  }

  static class RefType {
    private ServiceRouter serviceRouter;
    private Class<?> messageType;
    private String serviceName;
    private boolean aggregate;

    public void setServiceRouter(ServiceRouter serviceRouter) {
      this.serviceRouter = serviceRouter;
    }

    public ServiceRouter getServiceRouter() {
      return serviceRouter;
    }

    public boolean isAggregate() {
      return aggregate;
    }

    public void setAggregate(boolean aggregate) {
      this.aggregate = aggregate;
    }

    public Class<?> getMessageType() {
      return messageType;
    }

    public void setMessageType(Class<?> messageType) {
      this.messageType = messageType;
    }

    public String getServiceName() {
      return serviceName;
    }

    public void setServiceName(String serviceName) {
      this.serviceName = serviceName;
    }

  }

  /**
   * clear actor
   */
  void clear(String id) {
    syncActors.remove(id);
  }
}
