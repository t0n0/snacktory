package de.jetwick.snacktory;

import java.text.ParseException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jsoup.select.Selector.SelectorParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is thread safe.
 *
 * @author Alex P (ifesdjeen from jreadability)
 * @author Peter Karich
 */
public class ArticleTextExtractor {

    private static final Logger logger = LoggerFactory.getLogger(ArticleTextExtractor.class);
    // Interessting nodes
    private static final Pattern NODES = Pattern.compile("p|div|td|h1|h2|article|section");
    // Unlikely candidates
    private String unlikelyStr;
    private Pattern UNLIKELY;
    // Most likely positive candidates
    private String positiveStr;
    private Pattern POSITIVE;
    // Most likely negative candidates
    private String negativeStr;
    private Pattern NEGATIVE;
    private static final Pattern NEGATIVE_STYLE =
            Pattern.compile("hidden|display: ?none|font-size: ?small");
    private static final Pattern IGNORE_AUTHOR_PARTS =
        Pattern.compile("by|name|author|posted|twitter|handle|news", Pattern.CASE_INSENSITIVE);
    private static final Set<String> IGNORED_TITLE_PARTS = new LinkedHashSet<String>() {
        {
            add("hacker news");
            add("facebook");
            add("home");
            add("articles");
        }
    };
    private static final OutputFormatter DEFAULT_FORMATTER = new OutputFormatter();
    private OutputFormatter formatter = DEFAULT_FORMATTER;

    private static final int MIN_AUTHOR_NAME_LENGTH = 4;
    private static final List<Pattern> CLEAN_AUTHOR_PATTERNS = Arrays.asList(
        Pattern.compile("By\\S*(.*)[\\.,].*")
    );

    // For debugging
    private static final boolean DEBUG_WEIGHTS = false;
    private static final int MAX_LOG_LENGTH = 200;

    public ArticleTextExtractor() {
        setUnlikely("com(bx|ment|munity)|dis(qus|cuss)|e(xtra|[-]?mail)|foot|"
                + "header|menu|re(mark|ply)|rss|sh(are|outbox)|sponsor"
                + "a(d|ll|gegate|rchive|ttachment)|(pag(er|ination))|popup|print|"
                + "login|si(debar|gn|ngle)");
        setPositive("(^(body|content|h?entry|main|page|post|text|blog|story|haupt))"
                + "|arti(cle|kel)|instapaper_body");
        setNegative("nav($|igation)|user|com(ment|bx)|(^com-)|contact|"
                + "foot|masthead|(me(dia|ta))|outbrain|promo|related|scroll|(sho(utbox|pping))|"
                + "sidebar|sponsor|tags|tool|widget|player|disclaimer|toc|infobox|vcard|post-ratings");
    }

    public ArticleTextExtractor setUnlikely(String unlikelyStr) {
        this.unlikelyStr = unlikelyStr;
        UNLIKELY = Pattern.compile(unlikelyStr);
        return this;
    }

    public ArticleTextExtractor addUnlikely(String unlikelyMatches) {
        return setUnlikely(unlikelyStr + "|" + unlikelyMatches);
    }

    public ArticleTextExtractor setPositive(String positiveStr) {
        this.positiveStr = positiveStr;
        POSITIVE = Pattern.compile(positiveStr);
        return this;
    }

    public ArticleTextExtractor addPositive(String pos) {
        return setPositive(positiveStr + "|" + pos);
    }

    public ArticleTextExtractor setNegative(String negativeStr) {
        this.negativeStr = negativeStr;
        NEGATIVE = Pattern.compile(negativeStr);
        return this;
    }

    public ArticleTextExtractor addNegative(String neg) {
        setNegative(negativeStr + "|" + neg);
        return this;
    }

    public void setOutputFormatter(OutputFormatter formatter) {
        this.formatter = formatter;
    }

 // Returns the best node match based on the weights (see getWeight for strategy)
 	private Element getBestMatchElement(Collection<Element> nodes){
 		int maxWeight = -200;        // why -200 now instead of 0?
 		Element bestMatchElement = null;
 		
         boolean ignoreMaxWeightLimit = false;
         for (Element entry : nodes) {

             LogEntries entries = null;
             if (DEBUG_WEIGHTS)
                 entries = new LogEntries();
             int currentWeight = getWeight(entry, false, entries);
             if (DEBUG_WEIGHTS){
                 if(currentWeight>35){
                     System.out.println("-------------------------------------------");
                     System.out.println("         TAG: " + entry.tagName());
                     entries.print();
                     System.out.println("======================================");
                     System.out.println("                  TOTAL WEIGHT:" 
                                         + String.format("%3d", currentWeight));
                     String outerHtml = entry.outerHtml();
                     if (outerHtml.length() > MAX_LOG_LENGTH)
                         outerHtml = outerHtml.substring(0, MAX_LOG_LENGTH);
                     System.out.println(outerHtml);
                 }
             }
             if (currentWeight > maxWeight) {
                 maxWeight = currentWeight;
                 bestMatchElement = entry;

                 // The original code had a limit of 200, the intention was that
                 // if a node had a weight greater than it, then it most likely
                 // it was the main content.
                 // However this assumption fails when the amount of text in the 
                 // children (or grandchildren) is too large. If we detect this
                 // case then the limit is ignored and we try all the nodes to select
                 // the one with the absolute maximum weight.
                 if (maxWeight > 2000){
                     ignoreMaxWeightLimit = true;
                     continue;
                 } 

                 // formerly 200, increased to 250 to account for the fact
                 // we are not adding the weights of the grand children to the
                 // tally.
                 if (maxWeight > 250 && !ignoreMaxWeightLimit) 
                     break;
             }
         }

         return bestMatchElement;
     }
 	
    /**
     * @param html extracts article text from given html string. wasn't tested
     * with improper HTML, although jSoup should be able to handle minor stuff.
     * @returns extracted article, all HTML tags stripped
     */
    public JResult extractContent(Document doc) throws Exception {
        return extractContent(new JResult(), doc, formatter);
    }

    public JResult extractContent(Document doc, OutputFormatter formatter) throws Exception {
        return extractContent(new JResult(), doc, formatter);
    }

    public JResult extractContent(String html) throws Exception {
        return extractContent(new JResult(), html);
    }

    public JResult extractContent(JResult res, String html) throws Exception {
        return extractContent(res, html, formatter);
    }

    public JResult extractContent(JResult res, String html, OutputFormatter formatter) throws Exception {
        if (html.isEmpty())
            throw new IllegalArgumentException("html string is empty!?");

        // http://jsoup.org/cookbook/extracting-data/selector-syntax
        return extractContent(res, Jsoup.parse(html), formatter);
    }

    public JResult extractContent(JResult res, Document doc, OutputFormatter formatter) throws Exception {
        if (doc == null)
            throw new NullPointerException("missing document");

        res.setTitle(extractTitle(doc));
        res.setDescription(extractDescription(doc));
        res.setCanonicalUrl(extractCanonicalUrl(doc));
        res.setType(extractType(doc));
		res.setSitename(extractSitename(doc));
		res.setLanguage(extractLanguage(doc));
        res.setAuthorName(extractAuthorName(doc));
        res.setAuthorDescription(extractAuthorDescription(doc, res.getAuthorName()));

        // now remove the clutter
        prepareDocument(doc);

        // init elements
        Collection<Element> nodes = getNodes(doc);
        int maxWeight = 0;
        Element bestMatchElement = null;
        for (Element entry : nodes) {
            int currentWeight = getWeight(entry);
            if (currentWeight > maxWeight) {
                maxWeight = currentWeight;
                bestMatchElement = entry;
                if (maxWeight > 200)
                    break;
            }
        }

        if (bestMatchElement != null) {
            List<ImageResult> images = new ArrayList<ImageResult>();
            Element imgEl = determineImageSource(bestMatchElement, images);
            if (imgEl != null) {
                res.setImageUrl(SHelper.replaceSpaces(imgEl.attr("src")));
                // TODO remove parent container of image if it is contained in bestMatchElement
                // to avoid image subtitles flooding in

                res.setImages(images);
            }

            // clean before grabbing text
            String text = formatter.getFormattedText(bestMatchElement);
            text = removeTitleFromText(text, res.getTitle());
            // this fails for short facebook post and probably tweets: text.length() > res.getDescription().length()
            if (text.length() > res.getTitle().length()) {
                res.setText(text);
                // print("best element:", bestMatchElement);
            }
            res.setTextList(formatter.getTextList(bestMatchElement));
            // extract links from the same best element
            String fullhtml = bestMatchElement.toString();
            Elements children = bestMatchElement.select("a[href]"); // a with href = link
            String linkstr = "";
            Integer linkpos = 0;
            Integer lastlinkpos = 0;
            for (Element child : children) {
                linkstr = child.toString();
                linkpos = fullhtml.indexOf(linkstr, lastlinkpos);
                res.addLink(child.attr("abs:href"), child.text(), linkpos);
                lastlinkpos = linkpos;
            }
        }

        if (res.getImageUrl().isEmpty()) {
            res.setImageUrl(extractImageUrl(doc));
        }

        res.setRssUrl(extractRssUrl(doc));
        res.setVideoUrl(extractVideoUrl(doc));
        res.setFaviconUrl(extractFaviconUrl(doc));
        res.setKeywords(extractKeywords(doc));

        // Sanity checks in author description.
        String authorDescSnippet = getSnippet(res.getAuthorDescription());
        if (getSnippet(res.getText()).equals(authorDescSnippet) || 
             getSnippet(res.getDescription()).equals(authorDescSnippet)) {
            res.setAuthorDescription("");
        } else {
            if (res.getAuthorDescription().length() > 1000){
                res.setAuthorDescription(res.getAuthorDescription().substring(0, 1000));
            }
        }
        return res;
    }

    private static String getSnippet(String data){
        if (data.length() < 50)
            return data;
        else
            return data.substring(0, 50);
    }

    protected String extractTitle(Document doc) {
        String title = cleanTitle(doc.title());
        if (title.isEmpty()) {
            title = SHelper.innerTrim(doc.select("head title").text());
            if (title.isEmpty()) {
                title = SHelper.innerTrim(doc.select("head meta[name=title]").attr("content"));
                if (title.isEmpty()) {
                    title = SHelper.innerTrim(doc.select("head meta[property=og:title]").attr("content"));
                    if (title.isEmpty()) {
                        title = SHelper.innerTrim(doc.select("head meta[name=twitter:title]").attr("content"));
                    }
                }
            }
        }
        return title;
    }

    protected String extractCanonicalUrl(Document doc) {
        String url = SHelper.replaceSpaces(doc.select("head link[rel=canonical]").attr("href"));
        if (url.isEmpty()) {
            url = SHelper.replaceSpaces(doc.select("head meta[property=og:url]").attr("content"));
            if (url.isEmpty()) {
                url = SHelper.replaceSpaces(doc.select("head meta[name=twitter:url]").attr("content"));
            }
        }
        return url;
    }

    protected String extractDescription(Document doc) {
        String description = SHelper.innerTrim(doc.select("head meta[name=description]").attr("content"));
        if (description.isEmpty()) {
            description = SHelper.innerTrim(doc.select("head meta[property=og:description]").attr("content"));
            if (description.isEmpty()) {
                description = SHelper.innerTrim(doc.select("head meta[name=twitter:description]").attr("content"));
            }
        }
        return description;
    }

    // Returns the publication Date or null
	protected Date extractDate(Document doc) {
		String dateStr = "";

        // try some locations that nytimes uses
        Element elem = doc.select("meta[name=ptime]").first();
		if (elem != null) {
            dateStr = SHelper.innerTrim(elem.attr("content"));
            //            elem.attr("extragravityscore", Integer.toString(100));
            //            System.out.println("date modified element " + elem.toString());
        }

		if (dateStr == "") {
            dateStr = SHelper.innerTrim(doc.select("meta[name=utime]").attr("content"));
        }
		if (dateStr == "") {
            dateStr = SHelper.innerTrim(doc.select("meta[name=pdate]").attr("content"));
        }
		if (dateStr == "") {
            dateStr = SHelper.innerTrim(doc.select("meta[property=article:published]").attr("content"));
        }
		if (dateStr != "") {
            return parseDate(dateStr);
        }

        // taking this stuff directly from Juicer (and converted to Java)
        // opengraph (?)
        Elements elems = doc.select("meta[property=article:published_time]");
        if (elems.size() > 0) {
            Element el = elems.get(0);
            if (el.hasAttr("content")) {
                dateStr = el.attr("content");
                try {
                    if (dateStr.endsWith("Z")) {
                        dateStr = dateStr.substring(0, dateStr.length() - 1) + "GMT-00:00";
                    } else {
                        dateStr = "%sGMT%s".format(dateStr.substring(0, dateStr.length() - 6), 
                                                   dateStr.substring(dateStr.length() - 6, 
                                                                     dateStr.length()));
                    }
                } catch(StringIndexOutOfBoundsException ex) {
                    // do nothing
                } 
                return parseDate(dateStr);
            }
        } 

        // rnews 
        elems = doc.select("meta[property=dateCreated], span[property=dateCreated]");
        if (elems.size() > 0) {
            Element el = elems.get(0);
            if (el.hasAttr("content")) {
                dateStr = el.attr("content");
                
                return parseDate(dateStr);
            } else {
                return parseDate(el.text());
            }
        }

        // schema.org creativework
        elems = doc.select("meta[itemprop=datePublished], span[itemprop=datePublished]");
        if (elems.size() > 0) {
            Element el = elems.get(0);
            if (el.hasAttr("content")) {
                dateStr = el.attr("content");
                
                return parseDate(dateStr);
            } else if (el.hasAttr("value")) {
                dateStr = el.attr("value");

                return parseDate(dateStr);
            } else {
                return parseDate(el.text());
            }
        } 

        // parsely page (?)
        /*  skip conversion for now, seems highly specific and uses new lib
        elems = doc.select("meta[name=parsely-page]");
        if (elems.size() > 0) {
            implicit val formats = net.liftweb.json.DefaultFormats

                Element el = elems.get(0);
                if(el.hasAttr("content")) {
                    val json = parse(el.attr("content"))

                        return DateUtils.parseDateStrictly((json \ "pub_date").extract[String], Array("yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssZZ", "yyyy-MM-dd'T'HH:mm:ssz"))
                        }
            } 
        */
      
        // BBC
        elems = doc.select("meta[name=OriginalPublicationDate]");
        if (elems.size() > 0) {
            Element el = elems.get(0);
            if (el.hasAttr("content")) {
                dateStr = el.attr("content");
                return parseDate(dateStr);
            }
        }

        // wired
        elems = doc.select("meta[name=DisplayDate]");
        if (elems.size() > 0) {
            Element el = elems.get(0);
            if (el.hasAttr("content")) {
                dateStr = el.attr("content");
                return parseDate(dateStr);
            }
        }

        // wildcard
        elems = doc.select("meta[name*=date]");
        if (elems.size() > 0) {
            Element el = elems.get(0);
            if (el.hasAttr("content")) {
                dateStr = el.attr("content");
                Date parsedDate = parseDate(dateStr);
                if (parsedDate != null){
                    return parsedDate;
                }
            }
        }

        // blogger
        elems = doc.select(".date-header");
        if (elems.size() > 0) {
            Element el = elems.get(0);
            dateStr = el.text();
            return parseDate(dateStr);
        }

        return null;
    }

    private Date parseDate(String dateStr) {
        String[] parsePatterns = {
            "yyyy-MM-dd'T'HH:mm:ssz", 
            "yyyy-MM-dd HH:mm:ss", 
            "yyyy/MM/dd HH:mm:ss", 
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm",
            "yyyy-MM-dd", 
            "yyyy/MM/dd",
            "MM/dd/yyyy HH:mm:ss",
            "MM-dd-yyyy HH:mm:ss",
            "MM/dd/yyyy HH:mm",
            "MM-dd-yyyy HH:mm",
            "MM/dd/yyyy",
            "MM-dd-yyyy",
            "EEE, MMM dd, yyyy",
            "MM/dd/yyyy hh:mm:ss a",
            "MM-dd-yyyy hh:mm:ss a",
            "MM/dd/yyyy hh:mm a",
            "MM-dd-yyyy hh:mm a",
            "yyyy-MM-dd hh:mm:ss a", 
            "yyyy/MM/dd hh:mm:ss a ", 
            "yyyy-MM-dd hh:mm a",
            "yyyy/MM/dd hh:mm ",
            "dd MMM yyyy",
            "dd MMMM yyyy",
            "yyyyMMddHHmm",
            "yyyyMMdd HHmm",
            "dd-MM-yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm:ss",
            "dd MMM yyyy HH:mm:ss",
            "dd MMMM yyyy HH:mm:ss",
            "dd-MM-yyyy HH:mm",
            "dd/MM/yyyy HH:mm",
            "dd MMM yyyy HH:mm",
            "dd MMMM yyyy HH:mm",
            "yyyyMMddHHmmss",
            "yyyyMMdd HHmmss",
            "yyyyMMdd"
        };

      try {
          return parseDateStrictly(dateStr, parsePatterns);
      } catch (Exception ex) {
          return null;
      }
    }
    
    public static Date parseDateStrictly(String str, String[] parsePatterns) throws ParseException {
        return parseDateWithLeniency(str, parsePatterns, false);
    }
    
    private static Date parseDateWithLeniency(String str, String[] parsePatterns,
            boolean lenient) throws ParseException {
        if (str == null || parsePatterns == null) {
            throw new IllegalArgumentException("Date and Patterns must not be null");
        }
        
        SimpleDateFormat parser = new SimpleDateFormat();
        parser.setLenient(lenient);
        ParsePosition pos = new ParsePosition(0);
        for (int i = 0; i < parsePatterns.length; i++) {

            String pattern = parsePatterns[i];

            // LANG-530 - need to make sure 'ZZ' output doesn't get passed to SimpleDateFormat
            if (parsePatterns[i].endsWith("ZZ")) {
                pattern = pattern.substring(0, pattern.length() - 1);
            }
            
            parser.applyPattern(pattern);
            pos.setIndex(0);

            String str2 = str;
            // LANG-530 - need to make sure 'ZZ' output doesn't hit SimpleDateFormat as it will ParseException
            if (parsePatterns[i].endsWith("ZZ")) {
                int signIdx  = indexOfSignChars(str2, 0);
                while (signIdx >=0) {
                    str2 = reformatTimezone(str2, signIdx);
                    signIdx = indexOfSignChars(str2, ++signIdx);
                }
            }

            Date date = parser.parse(str2, pos);
            if (date != null && pos.getIndex() == str2.length()) {
                return date;
            }
        }
        throw new ParseException("Unable to parse the date: " + str, -1);
    }
    
    private static int indexOfSignChars(String str, int startPos) {
        int idx = indexOf(str, '+', startPos);
        if (idx < 0) {
            idx = indexOf(str, '-', startPos);
        }
        return idx;
    }
    
    public static int indexOf(String str, char searchChar, int startPos) {
        if (isEmpty(str)) {
            return -1;
        }
        return str.indexOf(searchChar, startPos);
    }
    
    public static boolean isEmpty(String str) {
        return str == null || str.length() == 0;
    }
    
    private static String reformatTimezone(String str, int signIdx) {
        String str2 = str;
        if (signIdx >= 0 &&
            signIdx + 5 < str.length() &&
            Character.isDigit(str.charAt(signIdx + 1)) &&
            Character.isDigit(str.charAt(signIdx + 2)) &&
            str.charAt(signIdx + 3) == ':' &&
            Character.isDigit(str.charAt(signIdx + 4)) &&
            Character.isDigit(str.charAt(signIdx + 5))) {
            str2 = str.substring(0, signIdx + 3) + str.substring(signIdx + 4);
        }
        return str2;
    }

    // Returns the author name or null
	protected String extractAuthorName(Document doc) {
		String authorName = "";
		
        // first try the Google Author tag
		Element result = doc.select("body [rel*=author]").first();
		if (result != null)
			authorName = SHelper.innerTrim(result.ownText());

        // if that doesn't work, try some other methods
		if (authorName.isEmpty()) {

            // meta tag approaches, get content
            result = doc.select("head meta[name=author]").first();
            if (result != null) {
                authorName = SHelper.innerTrim(result.attr("content"));
            }

            if (authorName.isEmpty()) {  // for "opengraph"
                authorName = SHelper.innerTrim(doc.select("head meta[property=article:author]").attr("content"));
            }
            if (authorName.isEmpty()) { // OpenGraph twitter:creator tag
            	authorName = SHelper.innerTrim(doc.select("head meta[property=twitter:creator]").attr("content"));
            }
            if (authorName.isEmpty()) {  // for "schema.org creativework"
                authorName = SHelper.innerTrim(doc.select("meta[itemprop=author], span[itemprop=author]").attr("content"));
            }

            // other hacks
			if (authorName.isEmpty()) {
				try{
                    // build up a set of elements which have likely author-related terms
                    // .X searches for class X
					Elements matches = doc.select("a[rel=author],.byline-name,.byLineTag,.byline,.author,.by,.writer,.address");

					if(matches == null || matches.size() == 0){
						matches = doc.select("body [class*=author]");
					}
					
					if(matches == null || matches.size() == 0){
						matches = doc.select("body [title*=author]");
					}

                    // a hack for huffington post
					if(matches == null || matches.size() == 0){
						matches = doc.select(".staff_info dl a[href]");
					}

                    // a hack for http://sports.espn.go.com/
                    if(matches == null || matches.size() == 0){
                        matches = doc.select("cite[class*=source]");
                    }

                    // select the best element from them
					if(matches != null){
						Element bestMatch = getBestMatchElement(matches);

						if(!(bestMatch == null))
						{
							authorName = bestMatch.text();
							
							if(authorName.length() < MIN_AUTHOR_NAME_LENGTH){
								authorName = bestMatch.text();
							}
							
							authorName = SHelper.innerTrim(IGNORE_AUTHOR_PARTS.matcher(authorName).replaceAll(""));
							
							if(authorName.indexOf(",") != -1){
								authorName = authorName.split(",")[0];
							}
						}
					}
				}
				catch(Exception e){
					System.out.println(e.toString());
				}
			}
		}

        for (Pattern pattern : CLEAN_AUTHOR_PATTERNS) {
            Matcher matcher = pattern.matcher(authorName);
            if(matcher.matches()){
                authorName = SHelper.innerTrim(matcher.group(1));
                break;
            }
        }

        return authorName;
    }

    // Returns the author description or null
    protected String extractAuthorDescription(Document doc, String authorName){

        String authorDesc = "";

        if(authorName.equals(""))
            return "";

        // Special case for entrepreneur.com
        Elements matches = doc.select(".byline > .bio");
        if (matches!= null && matches.size() > 0){
            Element bestMatch = matches.first(); // assume it is the first.
            authorDesc = bestMatch.text();
            return authorDesc;
        }
        
        // Special case for huffingtonpost.com
        matches = doc.select(".byline span[class*=teaser]");
        if (matches!= null && matches.size() > 0){
            Element bestMatch = matches.first(); // assume it is the first.
            authorDesc = bestMatch.text();
            return authorDesc;
        }

        try {
            Elements nodes = doc.select(":containsOwn(" + authorName + ")");
            Element bestMatch = getBestMatchElement(nodes);
            if (bestMatch != null)
                authorDesc = bestMatch.text();
        } catch(SelectorParseException se){
            // Avoid error when selector is invalid
        }

        return authorDesc;
    }

    protected Collection<String> extractKeywords(Document doc) {
        String content = SHelper.innerTrim(doc.select("head meta[name=keywords]").attr("content"));

        if (content != null) {
            if (content.startsWith("[") && content.endsWith("]"))
                content = content.substring(1, content.length() - 1);

            String[] split = content.split("\\s*,\\s*");
            if (split.length > 1 || (split.length > 0 && !"".equals(split[0])))
                return Arrays.asList(split);
        }
        return Collections.emptyList();
    }

    /**
     * Tries to extract an image url from metadata if determineImageSource
     * failed
     *
     * @return image url or empty str
     */
    protected String extractImageUrl(Document doc) {
        // use open graph tag to get image
        String imageUrl = SHelper.replaceSpaces(doc.select("head meta[property=og:image]").attr("content"));
        if (imageUrl.isEmpty()) {
            imageUrl = SHelper.replaceSpaces(doc.select("head meta[name=twitter:image]").attr("content"));
            if (imageUrl.isEmpty()) {
                // prefer link over thumbnail-meta if empty
                imageUrl = SHelper.replaceSpaces(doc.select("link[rel=image_src]").attr("href"));
                if (imageUrl.isEmpty()) {
                    imageUrl = SHelper.replaceSpaces(doc.select("head meta[name=thumbnail]").attr("content"));
                }
            }
        }
        return imageUrl;
    }

    protected String extractRssUrl(Document doc) {
        return SHelper.replaceSpaces(doc.select("link[rel=alternate]").select("link[type=application/rss+xml]").attr("href"));
    }

    protected String extractVideoUrl(Document doc) {
        return SHelper.replaceSpaces(doc.select("head meta[property=og:video]").attr("content"));
    }

    protected String extractFaviconUrl(Document doc) {
        String faviconUrl = SHelper.replaceSpaces(doc.select("head link[rel=icon]").attr("href"));
        if (faviconUrl.isEmpty()) {
            faviconUrl = SHelper.replaceSpaces(doc.select("head link[rel^=shortcut],link[rel$=icon]").attr("href"));
        }
        return faviconUrl;
    }
    	
    protected String extractType(Document doc) {
        String type = cleanTitle(doc.title());
        type = SHelper.innerTrim(doc.select("head meta[property=og:type]").attr("content"));
        return type;
    }

    protected String extractSitename(Document doc) {
        String sitename = SHelper.innerTrim(doc.select("head meta[property=og:site_name]").attr("content"));
        if (sitename.isEmpty()) {
        	sitename = SHelper.innerTrim(doc.select("head meta[name=twitter:site]").attr("content"));
        }
		if (sitename.isEmpty()) {
			sitename = SHelper.innerTrim(doc.select("head meta[property=og:site_name]").attr("content"));
		}
        return sitename;
    }

	protected String extractLanguage(Document doc) {
		String language = SHelper.innerTrim(doc.select("head meta[property=language]").attr("content"));
	    if (language.isEmpty()) {
	    	language = SHelper.innerTrim(doc.select("html").attr("lang"));
	    	if (language.isEmpty()) {
				language = SHelper.innerTrim(doc.select("head meta[property=og:locale]").attr("content"));
	    	}
	    }
	    if (!language.isEmpty()) {
			if (language.length()>2) {
				language = language.substring(0,2);
			}
		}
	    return language;
	}

    /**
     * Weights current element. By matching it with positive candidates and
     * weighting child nodes. Since it's impossible to predict which exactly
     * names, ids or class names will be used in HTML, major role is played by
     * child nodes
     *
     * @param e Element to weight, along with child nodes
     */
    protected int getWeight(Element e) {
        int weight = calcWeight(e);
        weight += (int) Math.round(e.ownText().length() / 100.0 * 10);
        weight += weightChildNodes(e);
        return weight;
    }

    /**
     * Weights current element. By matching it with positive candidates and
     * weighting child nodes. Since it's impossible to predict which exactly
     * names, ids or class names will be used in HTML, major role is played by
     * child nodes
     *
     * @param e Element to weight, along with child nodes
     */
    protected int getWeight(Element e, boolean checkextra, LogEntries logEntries) {
        int weight = calcWeight(e);
        if(logEntries!=null) logEntries.add("       ======>     BASE WEIGHT:" + String.format("%3d", weight));
        int ownTextWeight = (int) Math.round(e.ownText().length() / 100.0 * 10);
        weight+=ownTextWeight;
        if(logEntries!=null) logEntries.add("       ======> OWN TEXT WEIGHT:" + String.format("%3d", ownTextWeight));
        int childrenWeight = weightChildNodes(e, logEntries);
        weight+=childrenWeight;
        if(logEntries!=null) logEntries.add("       ======> CHILDREN WEIGHT:" + String.format("%3d", childrenWeight));

        // add additional weight using possible 'extragravityscore' attribute
        if (checkextra) {
            Element xelem = e.select("[extragravityscore]").first();
            if (xelem != null) {
                //                System.out.println("HERE found one: " + xelem.toString());
                weight += Integer.parseInt(xelem.attr("extragravityscore"));
                //                System.out.println("WITH WEIGHT: " + xelem.attr("extragravityscore"));
            }
        }

        return weight;
    }
    
    /**
     * Weights a child nodes of given Element. During tests some difficulties
     * were met. For instanance, not every single document has nested paragraph
     * tags inside of the major article tag. Sometimes people are adding one
     * more nesting level. So, we're adding 4 points for every 100 symbols
     * contained in tag nested inside of the current weighted element, but only
     * 3 points for every element that's nested 2 levels deep. This way we give
     * more chances to extract the element that has less nested levels,
     * increasing probability of the correct extraction.
     *
     * @param rootEl Element, who's child nodes will be weighted
     */
    protected int weightChildNodes(Element rootEl) {
        int weight = 0;
        Element caption = null;
        List<Element> pEls = new ArrayList<Element>(5);
        for (Element child : rootEl.children()) {
            String ownText = child.ownText();

            // if you are on a paragraph, grab all the text including that surrounded by additional formatting.
            if (child.tagName().equals("p")) ownText = child.text();

            int ownTextLength = ownText.length();
            if (ownTextLength < 20)
                continue;

            if (ownTextLength > 200)
                weight += Math.max(50, ownTextLength / 10);

            if (child.tagName().equals("h1") || child.tagName().equals("h2")) {
                weight += 30;
            } else if (child.tagName().equals("div") || child.tagName().equals("p")) {
                weight += calcWeightForChild(child, ownText);
                if (child.tagName().equals("p") && ownTextLength > 50)
                    pEls.add(child);

                if (child.className().toLowerCase().equals("caption"))
                    caption = child;
            }
        }

        // use caption and image
        if (caption != null)
            weight += 30;

        if (pEls.size() >= 2) {
            for (Element subEl : rootEl.children()) {
                if ("h1;h2;h3;h4;h5;h6".contains(subEl.tagName())) {
                    weight += 20;
                    // headerEls.add(subEl);
                } else if ("table;li;td;th".contains(subEl.tagName())) {
                    addScore(subEl, -30);
                }

                if ("p".contains(subEl.tagName()))
                    addScore(subEl, 30);
            }
        }
        return weight;
    }

    /**
     * Weights a child nodes of given Element. During tests some difficulties
     * were met. For instance, not every single document has nested paragraph
     * tags inside of the major article tag. Sometimes people are adding one
     * more nesting level. So, we're adding 4 points for every 100 symbols
     * contained in tag nested inside of the current weighted element, but only
     * 3 points for every element that's nested 2 levels deep. This way we give
     * more chances to extract the element that has less nested levels,
     * increasing probability of the correct extraction.
     *
     * @param rootEl Element, who's child nodes will be weighted
     */
    protected int weightChildNodes(Element rootEl, LogEntries logEntries) {
        int weight = 0;
        Element caption = null;
        List<Element> pEls = new ArrayList<Element>(5);

        for (Element child : rootEl.children()) {
            String ownText = child.ownText();
            int ownTextLength = ownText.length();
            if (ownTextLength < 20)
                continue;

            if(logEntries!=null) {
                logEntries.add("\t      CHILD TAG: " + child.tagName());
            }

            if (ownTextLength > 200){
                int childOwnTextWeight = Math.max(50, ownTextLength / 10);
                if(logEntries!=null)
                    logEntries.add("      CHILD TEXT WEIGHT:" 
                                   + String.format("%3d", childOwnTextWeight));
                weight += childOwnTextWeight;
            }

            if (child.tagName().equals("h1") || child.tagName().equals("h2")) {
                int h2h1Weight = 30;
                weight += h2h1Weight;
                if(logEntries!=null)
                    logEntries.add("\t   H1/H2 WEIGHT:" 
                                   + String.format("%3d", h2h1Weight));
            } else if (child.tagName().equals("div") || child.tagName().equals("p")) {
                int calcChildWeight = calcWeightForChild(child, ownText);
                weight+=calcChildWeight;
                if(logEntries!=null)
                    logEntries.add("\t   CHILD WEIGHT:" 
                                   + String.format("%3d", calcChildWeight));
                if (child.tagName().equals("p") && ownTextLength > 50)
                    pEls.add(child);

                if (child.className().toLowerCase().equals("caption"))
                    caption = child;
            }
        }

        //
        // Visit grandchildren, This section visits the grandchildren 
        // of the node and calculate their weights. Note that grandchildren
        // weights are only worth 1/3 of children's
        //
        int grandChildrenWeight = 0;
        int grandChildrenCount = 0;
        for (Element child2 : rootEl.children()) {

            if(logEntries!=null) {
                logEntries.add("\t    CHILD TAG: " + child2.tagName());
                //logEntries.add(child2.outerHtml());
            }

            // If the node looks negative don't include it in the weights
            // instead penalize the grandparent. This is done to try to 
            // avoid giving weigths to navigation nodes, etc.
            if (NEGATIVE.matcher(child2.id()).find() || 
                NEGATIVE.matcher(child2.className()).find()){
                if(logEntries!=null){
                    logEntries.add("\t  CHILD DISCARDED");
                }
                grandChildrenWeight-=30;
                continue;
            }

            for (Element grandchild : child2.children()) {
                int grandchildWeight = 0;
                String ownText = grandchild.ownText();
                int ownTextLength = ownText.length();
                if (ownTextLength < 20)
                    continue;

                if(logEntries!=null) {
                    logEntries.add("\t    GRANDCHILD TAG: " + grandchild.tagName());
                    //logEntries.add(grandchild.outerHtml());
                }
                grandChildrenCount+=1;

                if (ownTextLength > 200){
                    int childOwnTextWeight = Math.max(50, ownTextLength / 10);
                    if(logEntries!=null)
                        logEntries.add("    GRANDCHILD TEXT WEIGHT:" 
                                       + String.format("%3d", childOwnTextWeight));
                    grandchildWeight += childOwnTextWeight;
                }

                if (grandchild.tagName().equals("h1") || grandchild.tagName().equals("h2")) {
                    int h2h1Weight = 30;
                    grandchildWeight += h2h1Weight;
                    if(logEntries!=null)
                        logEntries.add("   GRANDCHILD H1/H2 WEIGHT:" 
                                       + String.format("%3d", h2h1Weight));
                } else if (grandchild.tagName().equals("div") || grandchild.tagName().equals("p")) {
                    int calcChildWeight = calcWeightForChild(grandchild, ownText);
                    grandchildWeight+=calcChildWeight;
                    if(logEntries!=null)
                        logEntries.add("   GRANDCHILD CHILD WEIGHT:" 
                                       + String.format("%3d", calcChildWeight));
                }

                if(logEntries!=null)
                    logEntries.add("\t GRANDCHILD WEIGHT:" 
                                   + String.format("%3d", grandchildWeight));
                grandChildrenWeight += grandchildWeight;
            }
        }

        if (grandChildrenCount <= 0)
            grandChildrenCount = 1;
        grandChildrenWeight = grandChildrenWeight / 3;
        if(logEntries!=null){
            logEntries.add("\t  GRANDCHILDREN WEIGHT:" 
                           + String.format("%3d", grandChildrenWeight));
            logEntries.add("\t   GRANDCHILDREN COUNT:" 
                           + String.format("%3d", grandChildrenCount));
        }
        weight+=grandChildrenWeight;

        // use caption and image
        if (caption != null){
            int captionWeight = 30;
            weight+=captionWeight;
            if(logEntries!=null)
                logEntries.add("\t CAPTION WEIGHT:" 
                               + String.format("%3d", captionWeight));
        }

        if (pEls.size() >= 2) {
            for (Element subEl : rootEl.children()) {
                if ("h1;h2;h3;h4;h5;h6".contains(subEl.tagName())) {
                    int h1h2h3Weight = 20;
                    weight += h1h2h3Weight;
                    if(logEntries!=null)
                        logEntries.add("  h1;h2;h3;h4;h5;h6 WEIGHT:" 
                                       + String.format("%3d", h1h2h3Weight));
                    // headerEls.add(subEl);
                } else if ("table;li;td;th".contains(subEl.tagName())) {
                    addScore(subEl, -30);
                }

                if ("p".contains(subEl.tagName()))
                    addScore(subEl, 30);
            }
        }
        return weight;
    }

    public void addScore(Element el, int score) {
        int old = getScore(el);
        setScore(el, score + old);
    }

    public int getScore(Element el) {
        int old = 0;
        try {
            old = Integer.parseInt(el.attr("gravityScore"));
        } catch (Exception ex) {
        }
        return old;
    }

    public void setScore(Element el, int score) {
        el.attr("gravityScore", Integer.toString(score));
    }

    private int calcWeightForChild(Element child, String ownText) {
        int c = SHelper.count(ownText, "&quot;");
        c += SHelper.count(ownText, "&lt;");
        c += SHelper.count(ownText, "&gt;");
        c += SHelper.count(ownText, "px");
        int val;
        if (c > 5)
            val = -30;
        else
            val = (int) Math.round(ownText.length() / 25.0);

        addScore(child, val);
        return val;
    }

    private int calcWeight(Element e) {
        int weight = 0;
        if (POSITIVE.matcher(e.className()).find())
            weight += 35;

        if (POSITIVE.matcher(e.id()).find())
            weight += 40;

        if (UNLIKELY.matcher(e.className()).find())
            weight -= 20;

        if (UNLIKELY.matcher(e.id()).find())
            weight -= 20;

        if (NEGATIVE.matcher(e.className()).find())
            weight -= 50;

        if (NEGATIVE.matcher(e.id()).find())
            weight -= 50;

        String style = e.attr("style");
        if (style != null && !style.isEmpty() && NEGATIVE_STYLE.matcher(style).find())
            weight -= 50;
        return weight;
    }

    public Element determineImageSource(Element el, List<ImageResult> images) {
        int maxWeight = 0;
        Element maxNode = null;
        Elements els = el.select("img");
        if (els.isEmpty())
            els = el.parent().select("img");        
        
        double score = 1;
        for (Element e : els) {
            String sourceUrl = e.attr("src");
            if (sourceUrl.isEmpty() || isAdImage(sourceUrl))
                continue;

            int weight = 0;
            int height = 0;
            try {
                height = Integer.parseInt(e.attr("height"));
                if (height >= 50)
                    weight += 20;
                else
                    weight -= 20;
            } catch (Exception ex) {
            }

            int width = 0;
            try {
                width = Integer.parseInt(e.attr("width"));
                if (width >= 50)
                    weight += 20;
                else
                    weight -= 20;
            } catch (Exception ex) {
            }
            String alt = e.attr("alt");
            if (alt.length() > 35)
                weight += 20;

            String title = e.attr("title");
            if (title.length() > 35)
                weight += 20;

            String rel = null;
            boolean noFollow = false;
            if (e.parent() != null) {
                rel = e.parent().attr("rel");
                if (rel != null && rel.contains("nofollow")) {
                    noFollow = rel.contains("nofollow");
                    weight -= 40;
                }
            }

            weight = (int) (weight * score);
            if (weight > maxWeight) {
                maxWeight = weight;
                maxNode = e;
                score = score / 2;
            }

            ImageResult image = new ImageResult(sourceUrl, weight, title, height, width, alt, noFollow);
            images.add(image);
        }

        Collections.sort(images, new ImageComparator());
        return maxNode;
    }

    /**
     * Prepares document. Currently only stipping unlikely candidates, since
     * from time to time they're getting more score than good ones especially in
     * cases when major text is short.
     *
     * @param doc document to prepare. Passed as reference, and changed inside
     * of function
     */
    protected void prepareDocument(Document doc) {
//        stripUnlikelyCandidates(doc);
        removeScriptsAndStyles(doc);
    }

    /**
     * Removes unlikely candidates from HTML. Currently takes id and class name
     * and matches them against list of patterns
     *
     * @param doc document to strip unlikely candidates from
     */
    protected void stripUnlikelyCandidates(Document doc) {
        for (Element child : doc.select("body").select("*")) {
            String className = child.className().toLowerCase();
            String id = child.id().toLowerCase();

            if (NEGATIVE.matcher(className).find()
                    || NEGATIVE.matcher(id).find()) {
//                print("REMOVE:", child);
                child.remove();
            }
        }
    }

    private Document removeScriptsAndStyles(Document doc) {
        Elements scripts = doc.getElementsByTag("script");
        for (Element item : scripts) {
            item.remove();
        }

        Elements noscripts = doc.getElementsByTag("noscript");
        for (Element item : noscripts) {
            item.remove();
        }

        Elements styles = doc.getElementsByTag("style");
        for (Element style : styles) {
            style.remove();
        }

        return doc;
    }

    private void print(Element child) {
        print("", child, "");
    }

    private void print(String add, Element child) {
        print(add, child, "");
    }

    private void print(String add1, Element child, String add2) {
        logger.info(add1 + " " + child.nodeName() + " id=" + child.id()
                + " class=" + child.className() + " text=" + child.text() + " " + add2);
    }

    private boolean isAdImage(String imageUrl) {
        return SHelper.count(imageUrl, "ad") >= 2;
    }

    /**
     * Match only exact matching as longestSubstring can be too fuzzy
     */
    public String removeTitleFromText(String text, String title) {
        // don't do this as its terrible to read
//        int index1 = text.toLowerCase().indexOf(title.toLowerCase());
//        if (index1 >= 0)
//            text = text.substring(index1 + title.length());
//        return text.trim();
        return text;
    }

    /**
     * based on a delimeter in the title take the longest piece or do some
     * custom logic based on the site
     *
     * @param title
     * @param delimeter
     * @return
     */
    private String doTitleSplits(String title, String delimeter) {
        String largeText = "";
        int largetTextLen = 0;
        String[] titlePieces = title.split(delimeter);

        // take the largest split
        for (String p : titlePieces) {
            if (p.length() > largetTextLen) {
                largeText = p;
                largetTextLen = p.length();
            }
        }

        largeText = largeText.replace("&raquo;", " ");
        largeText = largeText.replace("»", " ");
        return largeText.trim();
    }

    /**
     * @return a set of all important nodes
     */
    public Collection<Element> getNodes(Document doc) {
        Map<Element, Object> nodes = new LinkedHashMap<Element, Object>(64);
        int score = 100;
        for (Element el : doc.select("body").select("*")) {
            if (NODES.matcher(el.tagName()).matches()) {
                nodes.put(el, null);
                setScore(el, score);
                score = score / 2;
            }
        }
        return nodes.keySet();
    }

    public String cleanTitle(String title) {
        StringBuilder res = new StringBuilder();
//        int index = title.lastIndexOf("|");
//        if (index > 0 && title.length() / 2 < index)
//            title = title.substring(0, index + 1);

        int counter = 0;
        String[] strs = title.split("\\|");
        for (String part : strs) {
            if (IGNORED_TITLE_PARTS.contains(part.toLowerCase().trim()))
                continue;

            if (counter == strs.length - 1 && res.length() > part.length())
                continue;

            if (counter > 0)
                res.append("|");

            res.append(part);
            counter++;
        }

        return SHelper.innerTrim(res.toString());
    }
    
    

    /**
     * Comparator for Image by weight
     *
     * @author Chris Alexander, chris@chris-alexander.co.uk
     *
     */
    public class ImageComparator implements Comparator<ImageResult> {

        @Override
        public int compare(ImageResult o1, ImageResult o2) {
            // Returns the highest weight first
            return o2.weight.compareTo(o1.weight);
        }
    }
    
    /**
     *   Helper class to keep track of log entries.
     */
     private class LogEntries {

         List<String> entries;

         public LogEntries(){
             entries = new ArrayList();
         }

         public void add(String entry){
             this.entries.add(entry);
         }

         public void print(){
             for (String entry : this.entries) {
                 System.out.println(entry);
             }
         }
     }
     
}