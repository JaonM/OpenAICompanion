import java.util.*;
import java.util.prefs.*;
import java.io.*;
public class AcceptancePreferencesFactory implements PreferencesFactory {
 private final Preferences root=new Node(null,"");
 public AcceptancePreferencesFactory() {
  // Seed the fixture from inside the packaged app, matching the UI/worker Keychain identity.
  // No credentials appear in environment variables, process arguments, or test logs.
  try {
   Properties secret=new Properties();
   try (InputStream in=new FileInputStream(System.getProperty("companion.acceptance.secretFile"))) { secret.load(in); }
   Class<?> storeClass=Class.forName("com.openai.companion.desktop.DesktopSecretStore",true,ClassLoader.getSystemClassLoader());
   Object store=storeClass.getConstructor().newInstance();
   if (Boolean.getBoolean("companion.acceptance.cleanup")) {
    storeClass.getMethod("remove",String.class).invoke(store,secret.getProperty("account"));
    System.exit(0);
   }
   storeClass.getMethod("write",String.class,String.class).invoke(store,secret.getProperty("account"),secret.getProperty("token"));
  } catch (Exception error) { throw new IllegalStateException("Cannot seed isolated worker credential",error); }
  Preferences p=root.node("/com/openai/companion/desktop");
  p.put("endpoint",System.getProperty("companion.acceptance.modelEndpoint"));
  p.put("model",System.getProperty("companion.acceptance.model","acceptance-stub"));
 }
 public Preferences userRoot(){return root;}
 public Preferences systemRoot(){return root;}
 private static class Node extends AbstractPreferences {
  final Map<String,String> values=new HashMap<>();
  Node(AbstractPreferences parent,String name){super(parent,name);}
  protected void putSpi(String k,String v){values.put(k,v);}
  protected String getSpi(String k){return values.get(k);}
  protected void removeSpi(String k){values.remove(k);}
  protected void removeNodeSpi(){}
  protected String[] keysSpi(){return values.keySet().toArray(new String[0]);}
  protected String[] childrenNamesSpi(){return new String[0];}
  protected AbstractPreferences childSpi(String n){return new Node(this,n);}
  protected void syncSpi(){}
  protected void flushSpi(){}
 }
}
