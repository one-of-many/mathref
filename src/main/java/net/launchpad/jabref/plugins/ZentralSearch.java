package net.launchpad.jabref.plugins;
/*
 * GNU GENERAL PUBLIC LICENSE
 * Version 3, 29 June 2007
 * http://www.gnu.org/licenses/gpl.txt
 */

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;

import net.sf.jabref.GUIGlobals;
import net.sf.jabref.Globals;
import net.sf.jabref.OutputPrinter;

import net.sf.jabref.BibtexEntry;
import net.sf.jabref.BibtexDatabase;
import net.sf.jabref.imports.BibtexParser;

import net.sf.jabref.imports.EntryFetcher;
import net.sf.jabref.gui.ImportInspectionDialog;
import net.sf.jabref.imports.ImportInspector;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * This class handles searching for and obtaining BibTeX entries from
 * Mathematisches Zentralblatt.
 * 
 * 
 * @author Karin Herm
 */
public class ZentralSearch implements EntryFetcher {

	private static final String CHARSET = "UTF-8";
	private static Log log = LogFactory.getLog(ZentralSearch.class);
	private JTextField author = new JTextField();
	private JTextField title = new JTextField();
	private JTextField theAbstract = new JTextField();
	private JButton reset = new JButton("clear all");

	public JPanel getOptionsPanel() {
		JPanel pan = new JPanel();
		pan.setLayout(new java.awt.GridLayout(10, 1));
		pan.add(new JLabel("The above query field has to be filled."));
		pan.add(new JSeparator(SwingConstants.HORIZONTAL));
		pan.add(new JLabel("Below are additional query options."));
		pan.add(new JLabel("Search by author"));
		pan.add(author);
		pan.add(new JLabel("Search by title"));
		pan.add(title);
		pan.add(new JLabel("Search the abstract"));
		pan.add(theAbstract);
		reset.addActionListener(new ActionListener() {

			public void actionPerformed(ActionEvent arg0) {
				author.setText("");
				title.setText("");
				theAbstract.setText("");
			}
		});
		pan.add(reset);
		return pan;
	}

	public String getHelpPage() {
		return "http://www.zentralblatt-math.org/zbmath/help/search";
	}

	public URL getIcon() {
		String url = "http://www.zentralblatt-math.org/zbmath/zbmath.ico";
		try {
			return new URL(url);
		} catch (MalformedURLException ex) {
			log.error(ex);
		}
		return GUIGlobals.getIconUrl(url);
	}

	public String getKeyName() {
		return "Search Zentralblatt MATH";
	}

	public String getTitle() {
		return Globals.menuTitle(getKeyName());
	}

	public boolean processQuery(String key, ImportInspector dialog,
			OutputPrinter status) {
		try {

			status.setStatus(Globals.lang("Searching for ") + key);
			BibtexDatabase bd = importZentralblattEntries(key, status);
			if (bd == null) {
				return false;
			}
			/* Add the entry to the inspection dialog */
			int entryCount = bd.getEntryCount();

			if (dialog instanceof ImportInspectionDialog) {
				ImportInspectionDialog d = (ImportInspectionDialog) dialog;
				d.setIconImage(ImageIO.read(getIcon()));
				d.setTitle(Globals.lang("Search results") + " "
						+ Globals.lang("for") + ": " + key + " (" + entryCount
						+ ")");
			}

			status.setStatus("Adding fetched entries: " + entryCount);
			if (entryCount > 0) {
				int i = 0;
				for (BibtexEntry entry : bd.getEntries()) {
					i++;
					dialog.setProgress(i, entryCount);
					dialog.addEntry(entry);
				}
			}

		} catch (Exception e) {
			status.setStatus(Globals
					.lang("Error while fetching from Zentralblatt MATH")
					+ ": "
					+ e.getMessage());
			log.error(e.getMessage(), e);
		}
		return true;
	}

	public void stopFetching() {
	}

	private String getZentralblattUrl() {
		return "http://www.zentralblatt-math.org/zbmath/search/";
	}

	private BibtexDatabase importZentralblattEntries(String key,
			OutputPrinter status) {
		String url = getZentralblattUrl();
		HttpURLConnection zentralblattConnection = null;
		try {
			log.debug("Zentralblatt URL: " + url);
			URL ZBUrl = new URL(url);
			String query = constructQuery(key);

			if (StringUtils.isBlank(query)) {
				log.error("Search entry was empty.");
				status.showMessage("Please select or enter keyword");
				return null;

			}
			zentralblattConnection = (HttpURLConnection) ZBUrl.openConnection();
			zentralblattConnection.setDoOutput(true); // Triggers POST.
			zentralblattConnection
					.setRequestProperty("Accept-Charset", CHARSET);
			zentralblattConnection.setRequestProperty("Content-Type",
					"application/x-www-form-urlencoded;charset=" + CHARSET);
			zentralblattConnection.setRequestProperty("User-Agent", "Jabref");

			OutputStream output = null;
			try {
				output = zentralblattConnection.getOutputStream();
				output.write(query.getBytes(CHARSET));
			} finally {
				if (output != null)
					try {
						output.close();
					} catch (IOException e) {
						log.debug(e.getMessage(), e);
					}
			}

			Set<String> bibtex = fetchBibtexEntries(IOUtils
					.toString(zentralblattConnection.getInputStream()));
			return makeBibtexDB(bibtex);
		} catch (IOException e) {
			log.error("IO-Problem: " + e.getMessage(), e);
			status.showMessage(
					Globals.lang("An Exception ocurred while accessing '%0'",
							url) + "\n\n" + e.getLocalizedMessage(),
					Globals.lang(getKeyName()), JOptionPane.ERROR_MESSAGE);
		} catch (RuntimeException e) {
			log.error("General Problem: " + e.getMessage(), e);
			status.showMessage(
					Globals.lang(
							"An Error occurred while fetching from Mathematisches Zentralblatt (%0):",
							new String[] { url })
							+ "\n\n" + e.getMessage(), Globals
							.lang(getKeyName()), JOptionPane.ERROR_MESSAGE);
		}
		return null;
	}

	private String constructQuery(String key)
			throws UnsupportedEncodingException {
		String query = "";
		if (StringUtils.isNotBlank(key)) {
			query += "any=" + URLEncoder.encode(key, CHARSET);
		}
		;
		if (StringUtils.isNotBlank(author.getText())) {
			query += "&au=" + URLEncoder.encode(author.getText(), CHARSET);
		}
		;
		if (StringUtils.isNotBlank(title.getText())) {
			query += "&ti=" + URLEncoder.encode(title.getText(), CHARSET);
		}
		if (StringUtils.isNotBlank(theAbstract.getText())) {
			query += "&ab=" + URLEncoder.encode(theAbstract.getText(), CHARSET);
		}
		query = StringUtils.stripStart(query, "&");
		return query;
	}

	private BibtexDatabase makeBibtexDB(Set<String> bibtex) {
		if (bibtex.isEmpty()) {
			throw new RuntimeException("Nothing found! Try a different search.");
		}
		BibtexDatabase bib = new BibtexDatabase();
		for (String entry : bibtex) {
			try {
				Collection<BibtexEntry> parserRes = BibtexParser
						.fromString(replaceUmlaute(entry));
				if (null == parserRes || parserRes.isEmpty()) {
					throw new RuntimeException("No parsing result!");
				}
				for (BibtexEntry be : parserRes) {
					bib.insertEntry(be);
				}

			} catch (RuntimeException re) {
				log.error("Could not parse: " + entry);
				log.error(re.getMessage(), re);
			}
		}
		if (null == bib.getEntries() || bib.getEntries().isEmpty()) {
			throw new RuntimeException("Nothing found! Try a different search.");
		}
		return bib;
	}

	private String replaceUmlaute(String entry) {
		String res = StringUtils.replaceEach(entry, //
				new String[] { "\\\"a", "\\\"o", "\\\"u" }, //
				new String[] { "ä", "ö", "ü" });
		res = StringUtils.replaceEach(res, //
				new String[] { "\\\"A", "\\\"O", "\\\"U" }, //
				new String[] { "Ä", "Ö", "Ü" });
		return res;
	}

	private Set<String> fetchBibtexEntries(String page) {
		Set<String> indices = retrieveIndexIds(page);
		Set<String> returnBibtext = new HashSet<String>();
		URL indexUrl = null;
		if (indices.isEmpty()) {
			throw new RuntimeException("Search did not return any results.");
		}
		for (String i : indices) {
			try {
				indexUrl = new URL(getZentralblattUrl() + "?index_=" + i
						+ "&type_=bib");
				HttpURLConnection zbm = (HttpURLConnection) indexUrl
						.openConnection();
				zbm.setRequestProperty("User-Agent", "Jabref");
				returnBibtext.add(IOUtils.toString(zbm.getInputStream()));

			} catch (Exception e) {
				log.error("url: " + indexUrl + "\n" + e.getMessage(), e);
			}
		}
		log.debug("Found: " + returnBibtext.toString());
		return returnBibtext;
	}

	private Set<String> retrieveIndexIds(String page) {
		Set<String> res = new HashSet<String>();
		String[] inx = StringUtils.substringsBetween(page, "href=\"?index_=",
				"&amp;type_=");
		if (null != inx && inx.length > 0) {
			res.addAll(Arrays.asList(inx));
		}else{
			throw new RuntimeException("No resulting docs received.");
		}
		return res;
	}
}
