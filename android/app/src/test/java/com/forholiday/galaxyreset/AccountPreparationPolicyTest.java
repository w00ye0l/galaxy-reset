package com.forholiday.galaxyreset;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import com.forholiday.galaxyreset.AccountPreparationPolicy.Provider;

public class AccountPreparationPolicyTest {
    @Test public void ownerInventoryIncludesHiddenAccountsButExcludesHistoryAndProfiles() {
        String dump="User UserInfo{0:Owner:13}\n  Accounts: 2\n    Account {name=first, type=com.google}\n    Account {name=second, type=com.google}\n  history: removed Account {name=old, type=com.old}\nUser UserInfo{10:Work:30}\n Accounts: 1\n Account {name=work, type=com.work}\n";
        assertEquals(Set.of("com.google"),AccountPreparationPolicy.accountTypes(dump));
        assertTrue(AccountPreparationPolicy.accountTypes("Accounts: 0\n").isEmpty());
    }
    @Test public void missingPartialAndAmbiguousInventoriesFailClosed() {
        for(String dump:List.of("", "Accounts: 1\n", "Accounts: 0\nAccounts: 0\n", "User UserInfo{10:Work:30}\nAccounts: 0\n", "Accounts: 1\nAccount {malformed}\n"))
            assertThrows(IllegalArgumentException.class,()->AccountPreparationPolicy.accountTypes(dump));
    }
    @Test public void selectsEveryPresentNonSamsungAuthenticatorRegardlessOfEnabledState() {
        for(String type:List.of("com.google","com.google.android.gm.pop3","com.nhn.android.naveraccount",
                "com.google.android.apps.tachyon","com.microsoft.appmanager","com.microsoft.msa.ltw","com.google.work")) {
            Provider provider=new Provider(type,"com.example.provider","com.example.provider.Service");
            assertEquals(List.of(provider),AccountPreparationPolicy.select(Set.of(type),List.of(provider)));
        }
    }
    @Test public void neverTouchesSamsungOrSharedSamsungProviderPackages() {
        List<Provider> providers=List.of(new Provider("com.osp.app.signin","com.samsung.host","com.samsung.host.Auth"),
            new Provider("com.other","com.samsung.host","com.samsung.host.Other"),
            new Provider("com.samsung.core","com.samsung.core","com.samsung.core.Auth"));
        assertTrue(AccountPreparationPolicy.select(Set.of("com.osp.app.signin","com.other","com.samsung.core"),providers).isEmpty());
    }
    @Test public void unrelatedProvidersAndDuplicateComponentsAreNotEnabled() {
        Provider target=new Provider("com.google","com.google.android.gms","com.google.Auth");
        Provider unrelated=new Provider("com.naver","com.naver.app","com.naver.Auth");
        assertEquals(List.of(target),AccountPreparationPolicy.select(Set.of("com.google"),List.of(target,unrelated,target)));
        assertTrue(AccountPreparationPolicy.select(Set.of(),List.of(target,unrelated)).isEmpty());
    }
    @Test public void rejectsMalformedComponentMetadata() {
        assertThrows(IllegalArgumentException.class,()->AccountPreparationPolicy.select(Set.of("x"),
            List.of(new Provider("x","bad;package","bad.Service"))));
    }
    @Test public void disabledDataClearIsVerifiedAndFailureRemainsVisible() throws Exception {
        ResetPipelineTest.Fake fake=new ResetPipelineTest.Fake() {
            @Override public ResetPipeline.Result run(String cmd,int timeout) {
                commands.add(cmd);
                if(cmd.startsWith("pm list packages"))return new ResetPipeline.Result(0,"package:com.example.disabled\n");
                return new ResetPipeline.Result(0,"Failed");
            }
        };
        ResetPipeline pipeline=new ResetPipeline(fake,p->{},Set.of());pipeline.clear("com.example.disabled");
        assertFalse(pipeline.warnings.isEmpty());
        assertTrue(fake.commands.stream().anyMatch(c->c.startsWith("pm clear")));
        assertFalse(fake.commands.stream().anyMatch(c->c.startsWith("pm enable")));
    }
}
