/*
 * (C) 2018 by 3m5. Media GmbH. http://www.3m5.de
 */
package net.packsam;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.phantomjs.PhantomJSDriver;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * Main class.
 *
 * @author osterrath
 */
public class ConfluenceExporter {

	private final static Pattern SPACE_KEY_PATTERN = Pattern.compile(".*listpagetemplates\\.action\\?key=([^&]+)&?.*");

	/**
	 * Main method.
	 *
	 * @param args
	 * 		cli arguments
	 */
	public static void main(String[] args) {
		String confluenceUrl = args[0];
		String username = args[1];
		String password = args[2];
		File targetDir = new File(args[3]);

		new ConfluenceExporter().exportAllSpaces(confluenceUrl, username, password, targetDir);
	}

	/**
	 * Exports all  accessible Confluence spaces to the given target directory.
	 *
	 * @param confluenceUrl
	 * 		base URL for confluence
	 * @param username
	 * 		user name to login
	 * @param password
	 * 		password
	 */
	public void exportAllSpaces(String confluenceUrl, String username, String password, File targetDir) {
		PhantomJSDriver driver = new PhantomJSDriver();
		driver.manage().window().setSize(new Dimension(1920, 1080));

		try {
			driver.get(confluenceUrl);

			// login
			WebElement inputUsername = driver.findElement(By.id("os_username"));
			WebElement inputPassword = driver.findElement(By.id("os_password"));
			WebElement loginButton = driver.findElement(By.id("loginButton"));

			inputUsername.sendKeys(username);
			inputPassword.sendKeys(password);
			loginButton.click();

			// open "all spaces"
			driver.findElement(By.cssSelector("a.all-spaces-link")).click();

			// collect spaces
			List<String> spacesURLs = new ArrayList<>();
			String currentPage = getCurrentPage(driver);
			do {
				new WebDriverWait(driver, 30).until(d -> findSpacesListElements(d).size() > 0);
				findSpacesListElements(driver).forEach(webElement -> spacesURLs.add(webElement.getAttribute("href")));

				// search next button
				WebElement nextButton = driver.findElement(By.cssSelector("#space-search-result .aui-nav-next>a"));
				if (nextButton != null && nextButton.getAttribute("href") != null) {
					final String prevPage = currentPage;
					nextButton.click();
					new WebDriverWait(driver, 30).until(d -> !StringUtils.equals(getCurrentPage(d), prevPage));
					currentPage = getCurrentPage(driver);
				} else {
					break;
				}
			} while (true);

			// go to each space
			for (String spacesURL : spacesURLs) {
				driver.navigate().to(spacesURL);
				String spaceKey = extractSpaceKey(driver);
				if (spaceKey != null) {

					// got to space export page
					driver.navigate().to(confluenceUrl + "/spaces/exportspacewelcome.action?key=" + spaceKey);

					// select PDF radio button
					driver.findElement(By.id("format-export-format-pdf")).click();

					// click next button
					driver.findElement(By.cssSelector("form[name=\"export-space-choose-format\"] input[name=\"confirm\"]")).click();

					// select standard export
					WebElement contentOptionAll;
					try {
						contentOptionAll = driver.findElement(By.id("contentOptionAll"));
					} catch (Exception e) {
						// radio button not found -> no pages in space?
						continue;
					}
					contentOptionAll.click();

					// click export button
					driver.findElement(By.cssSelector("form[name=\"exportspaceform\"] input[name=\"confirm\"]")).click();

					// wait for download link
					new WebDriverWait(driver, 600).until(d -> d.findElement(By.cssSelector("#taskCurrentStatus>a")));
					WebElement downloadButton = driver.findElement(By.cssSelector("#taskCurrentStatus>a"));

					// download
					String downloadLink = downloadButton.getAttribute("href");
					downloadPDF(driver, downloadLink, targetDir);
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
			makeScreenshot(driver);
		}
	}

	/**
	 * Downloads the PDF document with the given URL.
	 *
	 * @param driver
	 * 		web driver
	 * @param link
	 * 		URL to PDF document
	 * @param targetDir
	 * 		target directory to save PDF in
	 */
	private void downloadPDF(WebDriver driver, String link, File targetDir) {
		try {
			targetDir.mkdirs();

			// create connection
			URL url = new URL(link);
			HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();
			httpURLConnection.setRequestMethod("GET");

			// add cookies
			Set<Cookie> cookies = driver.manage().getCookies();
			StringBuilder cookieString = new StringBuilder();
			for (Cookie cookie : cookies) {
				cookieString.append(cookie.getName()).append("=").append(cookie.getValue()).append(";");
			}
			httpURLConnection.addRequestProperty("Cookie", cookieString.toString());

			// download
			String baseName = FilenameUtils.getName(link);
			try (InputStream in = httpURLConnection.getInputStream()) {
				Files.copy(in, new File(targetDir, baseName).toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Extracts the space key from the current space overview page.
	 *
	 * @param driver
	 * 		web driver
	 * @return space key or <code>null</code>
	 */
	private String extractSpaceKey(WebDriver driver) {
		// look in space tools menu for space content page link
		List<WebElement> menuItems = driver.findElements(By.cssSelector("#space-tools-menu a[role=\"menuitem\"]"));
		for (WebElement menuItem : menuItems) {
			String href = menuItem.getAttribute("href");
			if (href != null) {
				Matcher m = SPACE_KEY_PATTERN.matcher(href);
				if (m.matches()) {
					return m.group(1);
				}
			}
		}
		return null;
	}

	/**
	 * Extracts the space links from the current space list page.
	 *
	 * @param d
	 * 		web driver
	 * @return list of link elements
	 */
	private List<WebElement> findSpacesListElements(WebDriver d) {
		return d.findElements(By.cssSelector("td.space-name>a"));
	}

	/**
	 * Reads the current page from the space list page.
	 *
	 * @param d
	 * 		web driver
	 * @return current page or <code>null</code>
	 */
	private String getCurrentPage(WebDriver d) {
		WebElement element = d.findElement(By.cssSelector("#space-search-result .aui-nav-selected"));
		if (element != null) {
			return element.getText();
		} else {
			return null;
		}
	}

	/**
	 * Creates a screenshot of the current page in the current directory.
	 *
	 * @param d
	 * 		web driver
	 */
	private void makeScreenshot(TakesScreenshot d) {
		File screenshot = d.getScreenshotAs(OutputType.FILE);
		try {
			FileUtils.copyFile(screenshot, new File(screenshot.getName()));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
