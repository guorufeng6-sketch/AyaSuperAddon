package com.turboio.addon;

import java.lang.reflect.*;

/** Version-scoped public API adapter. No vendor code copied. */
final class NavReflect {
    static Class<?> type(String name)throws Exception{return Class.forName(name);}
    static Object field(Object target,String name)throws Exception{return (target instanceof Class?(Class<?>)target:target.getClass()).getField(name).get(target instanceof Class?null:target);}
    private static Class<?> boxed(Class<?> c){if(c==boolean.class)return Boolean.class;if(c==int.class)return Integer.class;if(c==long.class)return Long.class;if(c==float.class)return Float.class;if(c==double.class)return Double.class;return c;}
    private static boolean fits(Class<?>[] types,Object[] args){if(types.length!=args.length)return false;for(int i=0;i<types.length;i++){if(args[i]==null){if(types[i].isPrimitive())return false;}else if(!boxed(types[i]).isInstance(args[i]))return false;}return true;}
    static Object make(String name,Object...args)throws Exception{for(Constructor<?> c:type(name).getConstructors())if(fits(c.getParameterTypes(),args))return c.newInstance(args);throw new NoSuchMethodException(name);}
    static Object call(Object o,String name,Object...args)throws Exception{Class<?> c=o instanceof Class?(Class<?>)o:o.getClass();for(Method m:c.getMethods())if(m.getName().equals(name)&&fits(m.getParameterTypes(),args))return m.invoke(o instanceof Class?null:o,args);throw new NoSuchMethodException(name);}
    interface Callback{void accept(String name,Object[] args)throws Exception;}
    static Object proxy(String name,Callback handler)throws Exception{Class<?> t=type(name);return Proxy.newProxyInstance(t.getClassLoader(),new Class[]{t},(o,m,a)->{if(m.getDeclaringClass()==Object.class){if(m.getName().equals("hashCode"))return System.identityHashCode(o);if(m.getName().equals("equals"))return o==a[0];return "TurboIOAdapter";}handler.accept(m.getName(),a==null?new Object[0]:a);return null;});}
}
