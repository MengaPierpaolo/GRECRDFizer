// Author: http://rhizomik.net/~roberto/

package net.rhizomik.grecrdfizer;

import com.hp.hpl.jena.rdf.model.*;
import com.hp.hpl.jena.vocabulary.DC;
import com.hp.hpl.jena.vocabulary.DCTerms;
import com.hp.hpl.jena.vocabulary.RDF;
import com.hp.hpl.jena.vocabulary.RDFS;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.tidy.Tidy;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.*;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PubsRDFizer 
{	
	private String swrc = "http://swrc.ontoware.org/ontology#";
	private String bibo = "http://purl.org/ontology/bibo/";

	private String pubBaseUri = "http://www.cretesos.udl.cat";
	
	private Resource Article = ResourceFactory.createResource(swrc+"Article");
	private Resource BookSection = ResourceFactory.createResource(swrc+"InBook"); 			// bibo:BookSection
	private Resource Journal = ResourceFactory.createResource(swrc+"Journal");				// Not in SWRC 0.3
	private Resource Book = ResourceFactory.createResource(swrc+"Book");
	private Property volumeProperty = ResourceFactory.createProperty(swrc+"volume");
	private Property issueProperty = ResourceFactory.createProperty(swrc+"number"); 		// bibo:issue
	private Property pagesProperty = ResourceFactory.createProperty(swrc+"pages");
	private Property pageStartProperty = ResourceFactory.createProperty(bibo+"pageStart");
	private Property pageEndProperty = ResourceFactory.createProperty(bibo+"pageEnd");
	private Property authorListProperty = ResourceFactory.createProperty(swrc+"authorList");// bibo:authorList, not in SWRC
	private Property authorsProperty = ResourceFactory.createProperty(swrc+"authors");      // Not in SWRC, literal with author list
	private Property issnProperty = ResourceFactory.createProperty(swrc+"issn");			// Not in SWRC
	private Property isbnProperty = ResourceFactory.createProperty(swrc+"isbn");
	private Property homepageProperty = ResourceFactory.createProperty(swrc+"homepage");
	
    private static final Logger log = Logger.getLogger(PubsRDFizer.class.getName());
    private static final int REVISTES = 2;
    private static final int LLIBRES = 5;
    private static final int LAST_YEAR = 12;
    private static final int ALL_YEARS = 2;
    
    private XPathExpression extraPagesExpr;
    private XPathExpression pubLinkExpr;
    private XPathExpression pubDetailExpr;
	
    private URL grecPubsUrl;
    private Document parsedGrecPubs;
    private XPath xpath;
    private Model pubs;
    
    private static String queryHead = "http://webgrec.udl.es/cgi-bin/DADREC/crcx1.cgi?PID=140816&QUE=CRXG&CRXGCODI=";
    private static String queryTail = "&CONSULTA=Fer+la+consulta";
    private static String GRIHO = "GRIHOXXX";
    private static String GREA = "ENERAPLI";

	public static void main(String[] args) 
		throws XPathExpressionException, MalformedURLException, IOException, ParserConfigurationException, SAXException 
	{
		String url = queryHead + GREA + queryTail;
		
		OutputStream out = System.out;
		
        if (args.length > 2)
        {
            System.out.println("Usage: PubsRDFizer [URL] [output.rdf]");
            System.exit(1);
        }
        if (args.length > 0)
        	url = args[0];
        if (args.length > 1)
            out = new FileOutputStream(new File(args[1]));
        
        PubsRDFizer rdfizer = new PubsRDFizer(new URL(url));
        rdfizer.rdfizePubsType(REVISTES, ALL_YEARS);
        rdfizer.pubs.write(out, "RDF/XML-ABBREV"); 
        //rdfizer.pubs.write(out, "N3");        
	}
	
	public PubsRDFizer(URL grecPubsUrl) throws XPathExpressionException, IOException, ParserConfigurationException, SAXException 
	{
		this.grecPubsUrl = grecPubsUrl;
		this.parsedGrecPubs = parseHTML(grecPubsUrl);
		this.pubs = ModelFactory.createDefaultModel();
		this.xpath = XPathFactory.newInstance().newXPath();
		NamespaceContext ctx = new NamespaceContext() {
			public String getNamespaceURI(String prefix) 
			{
				return "http://www.w3.org/1999/xhtml";
			}
			public Iterator<String> getPrefixes(String val) {
				return null;
			}
			public String getPrefix(String uri) {
				return null;
			}
		};
		xpath.setNamespaceContext(ctx);
		this.extraPagesExpr = xpath.compile("//a[@class='pagines' and not(contains(text(),'>')) and not(contains(text(),'<'))]");
		this.pubLinkExpr = xpath.compile("//p[@class='llista']/a");
		this.pubDetailExpr = xpath.compile("//p[@class]");
	}
	
	private void rdfizePubsType(int pubType, int fromYear) throws XPathExpressionException, IOException, ParserConfigurationException, SAXException
	{
		String pubsExpr = "/html/body/table/tr[2]/td/table/tr[2]/td/table/tr/td/table/tr[2]/td/table/tr[3]/td/table/tr["+pubType+"]/td["+fromYear+"]/a/@href";
	    XPathExpression expr = xpath.compile(pubsExpr);

	    String href = expr.evaluate(parsedGrecPubs);
	    if (href!="")
	    {
	    	log.log(Level.INFO, "RDFize publications year page: "+href);
	  	  	rdfizeYearPubs(new URL(href));
	  	  	rdfizePubsType(pubType, fromYear+1);
	    }
	}

	private void rdfizeYearPubs(URL yearPubsUrl) throws XPathExpressionException, IOException, ParserConfigurationException, SAXException 
	{     
	    Document yearFirstPage = parseHTML(yearPubsUrl);
	    rdfizeYearPubsPage(yearFirstPage);
	    
	    //Check if there are additional pages for the year
	    NodeList nodes = (NodeList)extraPagesExpr.evaluate(yearFirstPage, XPathConstants.NODESET);
	    for (int i = 0; i < nodes.getLength(); i++)
	    {
	    	Node node = nodes.item(i);
	    	String extraPageUrl = ((Attr)node.getAttributes().getNamedItem("href")).getValue();
			log.log(Level.INFO, "Processing extra years page number "+(i+1)+": "+extraPageUrl);
			rdfizeYearPubsPage(new URL(extraPageUrl));
		}
	}

	private void rdfizeYearPubsPage(URL yearPubsPageUrl) throws XPathExpressionException, IOException, ParserConfigurationException, SAXException
	{
	    Document yearPubsPage = parseHTML(yearPubsPageUrl);
	    rdfizeYearPubsPage(yearPubsPage);
	}
	
	private void rdfizeYearPubsPage(Document yearPubsPage) throws XPathExpressionException, IOException, ParserConfigurationException, SAXException
	{
	    NodeList nodes = (NodeList)pubLinkExpr.evaluate(yearPubsPage, XPathConstants.NODESET);
	    for (int i = 0; i < nodes.getLength(); i++)
	    {
	    	Node node = nodes.item(i);
	    	String href = ((Attr)node.getAttributes().getNamedItem("href")).getValue();
	    	if (href.indexOf("javascript")>=0)
	    		href = href.substring(href.indexOf('\'')+1, href.lastIndexOf('\''));
			log.log(Level.INFO, "Processing publication details page "+(i+1)+": "+href);
			rdfizePubPage(new URL(href));
	    }
	}

	private void rdfizePubPage(URL pubDetailsUrl) 
		throws IOException, ParserConfigurationException, SAXException, XPathExpressionException 
	{
		Document pubPage = parseHTML(pubDetailsUrl);
    	if (getInnerXML(pubPage).indexOf("docent")>0) return; //Ignorar publicaciones docentes
	
	    Resource pubRes = null;
	    String journalName = null, issn =null, bookTitle = null, isbn = null, start = null;
	    
		NodeList nodes = (NodeList)pubDetailExpr.evaluate(pubPage, XPathConstants.NODESET);
	    for (int n=0; n < nodes.getLength(); n++)
	    {
	    	Node node = nodes.item(n);
	    	String row = getInnerXML(node);
	    	row = row.substring(row.indexOf("\">")+2, row.lastIndexOf('<'));
	    	row = row.replace("\n", " ");
	    	row = row.replaceAll("<br/>", "");
	    	String[] splits = row.split("<\\/?b>");
		    
		    for (int i=1; i < splits.length; )
		    {
		      if (splits[i].indexOf("GREC")>=0 && splits[i+1]!="")
		      {
		        pubRes = pubs.createResource(pubBaseUri+"/pub/"+splits[i+1].trim()+"/");
		      }
		      else if (splits[i].indexOf("Autors")>=0 && splits.length>i+1)
		      {
		        String[] authors;
		        if (splits[i+1].indexOf(";")>0)
		        	authors = splits[i+1].split("(;|\\sand\\s)");
		        else
		        {
		          authors = splits[i+1].split("(,|\\sand\\s)");
		          if (authors.length==2 && authors[0].trim().indexOf(" ")<0)
		          {
		            authors = new String[1];
		          	authors[0] = splits[i+1];
		          }
		        }
		        RDFList authorList = pubs.createList();
		        String authorsLiteral = "";
		        for (int j=0; j<authors.length; j++)
		        {
		          authors[j] = processAuthorName(authors[j]);
		          Resource authorRes = generateAuthorResource(authors[j]);
		          pubs.add(pubRes, DC.creator, authorRes);
		          pubs.add(authorRes, RDFS.label, authors[j]);
		          authorList = authorList.with(authorRes);
		          authorsLiteral += authors[j];
		          if (j < authors.length-1)
		        	  authorsLiteral += "; ";
		        }
		        pubs.add(pubRes, authorListProperty, authorList);
		        pubs.add(pubRes, authorsProperty, authorsLiteral);
		      }
		      else if (splits[i].indexOf("Títol")>=0 && splits.length>i+1)
		      {
		        pubs.add(pubRes, DC.title, splits[i+1].trim());
		      }
		      else if (splits[i].indexOf("Revista")>=0 && splits.length>i+1)
		      {
		        pubs.add(pubRes, RDF.type, Article);
		        journalName = splits[i+1];
		        journalName = journalName.substring(journalName.indexOf("-")+1).trim();
		      }
		      else if (splits[i].indexOf("Referència")>=0 && splits.length>i+1)
		      {
		    	  pubs.add(pubRes, RDF.type, BookSection);
		    	  bookTitle = splits[i+1].trim();
		      }
		      else if (splits[i].indexOf("Volum")>=0 && splits.length>i+1)
		      {
		    	  String vol = splits[i+1].trim();
		    	  if (vol.length()>0) pubs.add(pubRes, volumeProperty, vol);
		      }
		      else if (splits[i].indexOf("inicial")>=0 && splits.length>i+1)
		      {
		    	  start = splits[i+1].trim();
		    	  //pubs.add(pubRes, pageStartProperty, start);
		      }
		      else if (splits[i].indexOf("final")>=0 && splits.length>i+1)
		      {
		    	  String end = splits[i+1].trim();
		    	  if (start.length()>0 || end.length()>0)
		    	  {
			    	  String interval = (start!=null?start:"")+"-"+end;
			    	  pubs.add(pubRes, pagesProperty, interval);
		    	  }
		      }
		      else if (splits[i].indexOf("ISSN")>=0 && splits.length>i+1)
		      {
		    	  issn = splits[i+1].trim();
		      }
		      else if (splits[i].indexOf("ISBN")>=0 && splits.length>i+1)
		      {
		    	  isbn = splits[i+1].trim();
		      }
		      else if (splits[i].indexOf("Any")>=0 && splits.length>i+1)
		      {
		    	  String date = splits[i+1].trim();
		    	  pubs.add(pubRes, DC.date, date);
		      }
		      else if (splits[i].indexOf("Enllaç")>=0 && splits.length>i+1)
		      {
		    	  String url = splits[i+1];
		    	  url = url.substring(url.indexOf("href=\"")+6);
		    	  url = url.substring(0, url.indexOf('"'));
		    	  pubs.add(pubRes, homepageProperty, url);
		      }
/*		      else if (splits[i].indexOf("Clau")>=0)
		      {
		        String kind = splits[i+1];
		        if (kind.indexOf("docent")>0)
		        	pubs.add(pubRes, RDF.type, Article);
		      }
*/		      else
		    	  log.log(Level.INFO, "Ignored "+splits[i]+": "+(splits.length>i+1?splits[i+1]:""));
		      i+=2;
		    }
	    }
	    if (journalName!=null)
	    {
	    	Resource journal = null;
	    	if (issn!=null && issn.length()>0)
	    	{
	    		journal = pubs.createResource(pubBaseUri+"/journal/"+issn+"/");
	    		pubs.add(journal, issnProperty, issn);
	    	}
	    	else
	    		journal = pubs.createResource();
	    	pubs.add(journal, RDF.type, Journal);
	    	pubs.add(journal, RDFS.label, journalName);
	    	pubs.add(pubRes, DCTerms.isPartOf, journal);
	    }
	    else if (bookTitle!=null && bookTitle.length()>0)
	    {
	    	Resource book = null;
	    	if (isbn!=null && isbn.length()>0)
	    	{
	    		book = pubs.createResource(pubBaseUri+"/book/"+isbn+"/");
		    	pubs.add(book, isbnProperty, isbn);
	    	}
	    	else
	    		book = pubs.createResource();
	    	pubs.add(book, RDF.type, Book);
	    	pubs.add(book, RDFS.label, bookTitle);
	    	pubs.add(pubRes, DCTerms.isPartOf, book);
	    }
	    else
	    {
	    	pubs.add(pubRes, RDF.type, Book);
	    }
	    	
	}
	
	// Generate URI in the base namespace using author name (surname plus initials without dots)
	private Resource generateAuthorResource(String authorName) 
	{
		String authorNameNoAccents = Normalizer.normalize(authorName, Form.NFD).
										replaceAll("\\p{InCombiningDiacriticalMarks}+", ""); 
		String authorID = authorNameNoAccents.replace(".", "").replace(" ", "").replace(",", "");
		String authorURI = pubBaseUri+"/person/"+authorID+"/";
		
		return pubs.createResource(authorURI);
	}

	private String processAuthorName(String input)
	{
		String output = input;
		
        String name = input.replace(",", " ");  //Remove commas in author name
        name = name.replace(".", " "); //Remove points in author name
        name = name.trim();
        
        String names = null;
        String surname = null;
        
        // Process authornames like Smith J, Doe J R or Frost RA
        //if (input.indexOf(',')>0)
        //{
	        Pattern pattern = Pattern.compile("(\\S\\S+)\\s+(\\S\\S?)(?:\\s+(\\S))?$"); 
	        Matcher matcher = pattern.matcher(name); 
	        if (matcher.find()) 
	        { 
	        	surname = matcher.group(1);
	        	if (matcher.group(2).length()>1)
	        		names = matcher.group(2).charAt(0)+"."+matcher.group(2).charAt(1)+".";
	        	else
	        		names = matcher.group(2)+".";
	        	if (matcher.group(3)!=null)
	        		names += matcher.group(3)+".";
	        	output = surname+", "+names;
	        }
        //}
        // Process authornames like J Smith, J R Doe or RA Frost
        else
        {
        	//Pattern 
        	pattern = Pattern.compile("^(\\S\\S?)\\s+(:?(\\S)\\s+)?(\\S\\S+)");
        	//Matcher 
        	matcher = pattern.matcher(name);
        	if (matcher.find()) 
            { 
        		surname = matcher.group(4);
            	if (matcher.group(1).length()>1)
            		names = matcher.group(1).charAt(0)+"."+matcher.group(1).charAt(1)+".";
            	else
            		names = matcher.group(1)+".";
            	if (matcher.group(3)!=null)
            		names += matcher.group(3)+".";
            	output = surname+", "+names;
            }
            // Process authornames like John Smith or Ralph Albert Frost
        	else
        	{
            	pattern = Pattern.compile("^(\\S+)\\s+(:?(\\S+)\\s+)?(\\S+)");
            	matcher = pattern.matcher(name);
            	if (matcher.find()) 
                { 
            		surname = matcher.group(4);
            		names = matcher.group(1).charAt(0)+".";
                	if (matcher.group(3)!=null)
                		names += matcher.group(3).charAt(0)+".";
                	output = surname+", "+names;
                }
        	}
        }
        
        //name = name.replace(/(\S+)\s(\S+)\s(\S+)/, "$1 $2$3"); //Remove space between initials
        //if (name.charAt(name.length-1)!='.') name+=".";
        //name = name.replace(/Gonzalez/, "Gonz·lez");
        //name = name.replace(/Abello/, "AbellÛ");
        //if (name.indexOf("Collazos")>=0) name = "Collazos, C.A.";
        //if (name == "Gonz·lez.") name = "Gonz·lez MP.";
		
		return output;
	}

    private Document parseHTML(URL htmlDocUrl) throws IOException, ParserConfigurationException, SAXException  
	{
		StringWriter xhtmlWriter = new StringWriter();	

		Tidy tidy = new Tidy();
		tidy.setXHTML(true);
		tidy.setInputEncoding("ISO-8859-1");
		tidy.setOutputEncoding("UTF-8");
		tidy.setForceOutput(true);
		tidy.setQuiet(true);
		tidy.setShowWarnings(false);
		tidy.parse(htmlDocUrl.openStream(), xhtmlWriter);
		
		StringReader in = new StringReader(xhtmlWriter.toString());

		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(false);
		dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		DocumentBuilder builder = dbf.newDocumentBuilder();
		Document xmlDoc = builder.parse(new InputSource(in));
			
	    return xmlDoc;
	}
    
    private String getInnerXML(Node n)
    {
    	String xmlString = "";
    	try
    	{
	    	Transformer transformer = TransformerFactory.newInstance().newTransformer();
	    	transformer.setOutputProperty(OutputKeys.INDENT, "no");
	    	StreamResult result = new StreamResult(new StringWriter());
	    	DOMSource source = new DOMSource(n);
	    	transformer.transform(source, result);
	    	xmlString = result.getWriter().toString();
    	}
    	catch(Exception e)
    	{}
    	
    	return xmlString;
    }
}
