package io.kimmking.rpcfx.client;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.ParserConfig;
import io.kimmking.rpcfx.api.*;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public final class Rpcfx {

    static {
        ParserConfig.getGlobalInstance().addAccept("io.kimmking");
    }

    public static <T, filters> T createFromRegistry(final Class<T> serviceClass, final String zkUrl, Router router, LoadBalancer loadBalance, Filter filter) {

        // 加filte之一

        String service = serviceClass.getCanonicalName();//"io.kimking.rpcfx.demo.api.UserService";
        System.out.println("====> "+service);
        List<String> invokers = new ArrayList<>();

        RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
        CuratorFramework client = CuratorFrameworkFactory.builder().connectString("localhost:2181").namespace("rpcfx").retryPolicy(retryPolicy).build();
        client.start();

        try {
//        ServiceProviderDesc userServiceSesc = ServiceProviderDesc.builder()
//                .host(InetAddress.getLocalHost().getHostAddress())
//                .port(8082).serviceClass(service).build();
        // String userServiceSescJson = JSON.toJSONString(userServiceSesc);


            if ( null == client.checkExists().forPath("/" + service)) {
                return null;
            }

            fetchInvokers(client, service, invokers);

            final TreeCache treeCache = TreeCache.newBuilder(client, "/" + service).setCacheData(true).setMaxDepth(2).build();
            treeCache.getListenable().addListener(new TreeCacheListener() {
                public void childEvent(CuratorFramework curatorFramework, TreeCacheEvent treeCacheEvent) throws Exception {
                    System.out.println("treeCacheEvent: "+treeCacheEvent);
                    fetchInvokers(client, service, invokers);
                }
            });
            treeCache.start();

        } catch (Exception ex) {
            ex.printStackTrace();
        }

//
//
//		// register service
//		// xxx "io.kimmking.rpcfx.demo.api.UserService"
//


        // curator Provider list from zk

        // 1. 简单：从zk拿到服务提供的列表
        // 2. 挑战：监听zk的临时节点，根据事件更新这个list（注意，需要做个全局map保持每个服务的提供者List）

        return (T) create(serviceClass, invokers, router, loadBalance, filter);

    }



    private static void fetchInvokers(CuratorFramework client, String service, List<String> invokers) throws Exception {
        List<String> services = client.getChildren().forPath("/" + service);
        invokers.clear();
        for (String svc : services) {
            System.out.println(svc);
            String url = svc.replace("_", ":");
            invokers.add("http://" + url);
        }
    }

    private static <T> Object create(Class<T> serviceClass, List<String> invokers, Router router, LoadBalancer loadBalance, Filter... filters) {
        // 0. 替换动态代理 -> 字节码生成
        return (T) Proxy.newProxyInstance(Rpcfx.class.getClassLoader(), new Class[]{serviceClass},
                new RpcfxInvocationHandler(serviceClass, invokers, router, loadBalance, filters));
    }

}
