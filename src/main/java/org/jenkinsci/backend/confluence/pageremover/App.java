package org.jenkinsci.backend.confluence.pageremover;

import hudson.plugins.jira.soap.ConfluenceSoapService;
import hudson.plugins.jira.soap.RemotePage;
import hudson.plugins.jira.soap.RemotePageSummary;
import hudson.plugins.jira.soap.RemoteSearchResult;
import hudson.plugins.jira.soap.RemoteSpaceSummary;
import hudson.plugins.jira.soap.RemoteUserInformation;

import javax.xml.rpc.ServiceException;
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class App {
    private final Connection con;

    /**
     * Key of Wiki space, like "JENKINS"
     */
    private final String space = "JENKINS";
    private SpellChecker spellChecker;

    public static void main(String[] args) throws Exception {
        new App().run();
    }

    public void run() throws RemoteException {
        spellCheck();
    }

    private SpellChecker getSpellChecker() {
        if (spellChecker==null)
            spellChecker = new SpellChecker();
        return spellChecker;
    }

    public App() throws IOException, ServiceException {
        con = new Connection();
    }

    /**
     * Just lists all the users so that I can see if spammers are still signing up or if they are just
     * using accounts that they've already created.
     */
    private static void justListUsers(ConfluenceSoapService service, String token) throws RemoteException {
        for (String id : service.getActiveUsers(token,true)) {
            System.out.println(id);
        }
    }

    /**
     * Pages that have bogus titles, and pages that are just bookmarks.
     */
    private static void removeSpamBookmarkPages(ConfluenceSoapService service, String token) throws RemoteException {
        int n=0;
        for (RemotePageSummary p : service.getPages(token,"JENKINS")) {
            if (isForbidden(p.getTitle())) {
                System.out.println(p.getTitle());
//                service.removePage(token,p.getId());
                n++;
            }
            RemotePage page = service.getPage(token, p.getId());
            String content = page.getContent().trim();
            if (content.startsWith("{bookmark") && page.getContent().endsWith("{bookmark}")) {
                System.out.println(p.getTitle());
                n++;
            }
        }

        System.out.println(n);
    }

    private static void removeSpamProfiles(ConfluenceSoapService service, String token) throws Exception {
        Set<Pattern> patterns = compile("I have found shop where", "buy [a-zA-Z]+ here","downloaded movies will work perfectly","nude video","lowest prices on the web");

        OUTER:
        for (String id : service.getActiveUsers(token,true)) {
            RemoteUserInformation info = service.getUserInformation(token, id);
            if (info.getContent().length()==0)  continue;

            String content = info.getContent().toLowerCase();
            for (Pattern p : patterns) {
                if (p.matcher(content).find()) {
                    service.removeUser(token,id);
                    continue OUTER;
                }
            }

            System.out.println(id+":"+info.getContent());
        }
    }

    /**
     * If the user full name includes forbidden words, remove them.
     * If the user personal space includes forbidden words, remove them.
     */
    private static void removeSpamUsers(ConfluenceSoapService service, String token) throws RemoteException {
        int n=0;
        Set<String> spaceIds = new HashSet<String>();
        for (RemoteSpaceSummary ss : service.getSpaces(token))
            spaceIds.add(ss.getKey());

        for (String id : service.getActiveUsers(token,true)) {
//            RemoteUser u = service.getUser(token, id);
//            if (isForbidden(u.getFullname())) {
//                System.out.println(u.getFullname());
//                service.removeUser(token, id);
//            }

            // check profile for forbidden words
            RemoteUserInformation info = service.getUserInformation(token, id);
            if (getForbiddenCount(info.getContent())>4) {
                System.out.println(id);
                service.removeUser(token,id);
            }

//            if (spaceIds.contains("~"+id)) {
//                try {
//                    RemotePage home = service.getPage(token, service.getSpace(token,"~"+id).getHomePage());
//                    if (getForbiddenCount(home.getContent())>4) {
//                        service.removeUser(token,id);
//                    }
//                } catch (RemoteException e) {
//                    e.printStackTrace();
//                    // continue
//                }
//            }

            n++;
        }

        System.out.println(n);
    }

    /**
     * My attempt at listing pages that recently changed, but didn't work
     * unless some query string is supplied.
     */
    private void search() throws Exception {
        HashMap params = new HashMap();
        params.put("type","page");
        params.put("modified", "LASTWEEK");
        for (RemoteSearchResult r : con.service.search(con.token, "", params, 1000)) {
            System.out.println(r.getTitle());
        }
    }

    public class PossibleSpamPage {
        /**
         * 0-100 of likeliness of SPAM. 100 is definitely spam, 0 is probably not.
         */
        public final float score;

        public final RemotePage page;

        public PossibleSpamPage(float score, RemotePage page) {
            this.score = score;
            this.page = page;
        }

        public void delete() throws RemoteException {
            con.service.removePage(con.token, page.getId());
        }

        public boolean isVeryLikelySpam() {
            return score > 60;
        }
    }

    /**
     * Common spams we see involves pages that doesn't look like English.
     *
     * This function uses spell checker to pick up pages that appear to be non-English.
     */
    public List<PossibleSpamPage> spellCheck() throws RemoteException {
        List<PossibleSpamPage> r = new ArrayList<PossibleSpamPage>();
        for (RemotePageSummary p : con.getPages(space)) {
            RemotePage pg = con.getPage(p.getId());

            // we only care about recently updated pages
            if (olderThanDays(pg.getModified(),14))
                continue;

            float f = rateOf(pg);

            // page created by new users are more likely spam
            RemoteUserInformation u = con.getUserInformation(pg.getModifier());
            if (olderThanDays(u.getCreationDate(),14))
                f += 10;

            r.add(new PossibleSpamPage(f,pg));

            System.out.printf("%02.2f\t%16s\t%s\n",f,pg.getModifier(),p.getTitle());
        }

        return r;
    }

    private static final Set<String> FLAG_WORDS = new HashSet<String>(Arrays.asList(
            "watch","season","episode","free","streaming","live","hdqtv","now","movie"
    ));

    /**
     * A spam attack of Nov 2013 involves lots of pages whose page titles tries
     * to send people to watch some kind of online videos.
     */
    public List<PossibleSpamPage> watchFreeVideo() throws RemoteException {
        List<PossibleSpamPage> r = new ArrayList<PossibleSpamPage>();
        for (RemotePageSummary p : con.getPages(space)) {
            float f=0;
            for (String token : p.getTitle().split(" "))
                if (FLAG_WORDS.contains(token.toLowerCase()))
                    f+=20;

            if (f<10)   continue;

            RemotePage pg = con.getPage(p.getId());

            // page created by new users are more likely spam
            RemoteUserInformation u = con.getUserInformation(pg.getModifier());
            if (!olderThanDays(u.getCreationDate(),14))
                f += 10;

            r.add(new PossibleSpamPage(f,pg));

            if (f<10)
                continue;

            System.out.printf("%02.2f\t%16s\t%s\n",f,pg.getModifier(),p.getTitle());
        }

        return r;
    }

    private boolean olderThanDays(Calendar c,int n) {
        return c.getTimeInMillis()+TimeUnit.DAYS.toMillis(n) < System.currentTimeMillis();
    }

    private float rateOf(String title) throws RemoteException {
        return rateOf(con.getPage(space, title));
    }

    private float rateOf(RemotePage p) {
        String text = p.getContent();
        text = new ConfluenceMarkupNormalizer().translate(text);
        return getSpellChecker().errorRateOf(text);
    }


    private void removeSpamPages() throws RemoteException {
        int cnt = 0;
        for (RemotePageSummary p : con.getPages(space)) {
            if (isForbidden(p.getTitle())) {
                System.out.println(p.getTitle());
//                service.removePage(token,p.getId());
                cnt++;
            }
        }
        System.out.printf("%d pages removed", cnt);
    }

    /**
     * If it contains any of the forbidden words?
     */
    private static boolean isForbidden(String title) {
        for (Pattern p : FORBIDDEN) {
            if (p.matcher(title).find())
                return true;
        }
        return false;
    }

    private static int getForbiddenCount(String title) {
        int r = 0;
        for (Pattern p : FORBIDDEN) {
            Matcher m = p.matcher(title);
            while (m.find())
                r++;
        }
        return r;
    }

    private static Set<Pattern> compile(String... words) {
        Set<Pattern> r = new HashSet<Pattern>();
        for (String w : words)
            r.add(Pattern.compile("\\b"+w+"\\b",Pattern.CASE_INSENSITIVE));
        return r;
    }

    private static final Set<Pattern> FORBIDDEN = compile(
            "adipex",
            "ambien",
            "best price",
            "best prices",
            "buy",
            "cheap",
            "cheaper",
            "cheapest",
            "cialis",
            "cigarettes",
            "drug information",
            "floricet",
            "get it now",
            "hydrocodone",
            "insomnia",
            "klonopin",
            "kamagra",
            "levitra",
            "lolita",
            "medication",
            "medications",
            "novladex",
            "no rx",
            "nude",
            "online here",
            "order",
            "patient",
            "phentermine",
            "pharamacy",
            "pharmacy",
            "porn",
            "pregnancy",
            "prescription",
            "prozac",
            "purchase",
            "ringtones",
            "tamiflu",
            "testosterone",
            "viagra",
            "valium",
            "xanax",
            "zyban",

            "\0"
    );
}
