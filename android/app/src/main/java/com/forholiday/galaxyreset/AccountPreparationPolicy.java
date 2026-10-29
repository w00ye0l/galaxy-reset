package com.forholiday.galaxyreset;

import java.util.*;

/** Pure selection policy shared by the shell helper and regression tests. */
public final class AccountPreparationPolicy {
    public static final class Provider {
        public final String type, pkg, service;
        public Provider(String type, String pkg, String service) { this.type=type; this.pkg=pkg; this.service=service; }
    }
    /** Strict current owner inventory; history/other profiles cannot prove completion. */
    public static Set<String> accountTypes(String dump) { return new HashSet<>(accountList(dump)); }
    public static List<String> accountList(String dump) {
        java.util.regex.Matcher users=java.util.regex.Pattern.compile("(?m)^\\s*User UserInfo\\{(\\d+):").matcher(dump);
        int start=-1,end=dump.length();boolean hasUsers=false;
        while(users.find()) {
            hasUsers=true;
            if(start>=0){end=users.start();break;}
            if(users.group(1).equals("0"))start=users.end();
        }
        if(hasUsers&&start<0)throw new IllegalArgumentException("Owner inventory missing");
        if(start>=0)dump=dump.substring(start,end);
        java.util.regex.Matcher count=java.util.regex.Pattern.compile("(?m)^\\s*Accounts:\\s*(\\d+)\\s*$").matcher(dump);
        if(!count.find())throw new IllegalArgumentException("Inventory missing");
        int expected=Integer.parseInt(count.group(1));
        if(count.find())throw new IllegalArgumentException("Ambiguous inventory");
        List<String> types=new ArrayList<>();int actual=0;
        for(String line:dump.split("\\n")) {
            line=line.trim();if(!line.startsWith("Account {"))continue;
            java.util.regex.Matcher type=java.util.regex.Pattern.compile(",\\s*type=([A-Za-z0-9_.-]+)\\s*\\}$").matcher(line);
            if(!type.find())throw new IllegalArgumentException("Malformed account");
            types.add(type.group(1));actual++;
        }
        if(expected!=actual)throw new IllegalArgumentException("Incomplete inventory");
        return types;
    }
    public static boolean samsung(String type) { return type.startsWith("com.samsung") || type.startsWith("com.osp.app.signin"); }
    public static List<Provider> select(Set<String> types, List<Provider> providers) {
        Set<String> protectedPackages=new HashSet<>();
        for(Provider p:providers)if(samsung(p.type))protectedPackages.add(p.pkg);
        Map<String,Provider> result=new LinkedHashMap<>();
        for(Provider p:providers) {
            if(!types.contains(p.type)||samsung(p.type)||protectedPackages.contains(p.pkg))continue;
            if(!p.pkg.matches("[A-Za-z0-9_.]+")||!p.service.matches("[A-Za-z0-9_.$]+"))
                throw new IllegalArgumentException("Invalid authenticator component");
            result.put(p.pkg+"/"+p.service,p);
        }
        return new ArrayList<>(result.values());
    }
}
