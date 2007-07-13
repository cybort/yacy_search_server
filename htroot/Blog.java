// Blog.java 
// -----------------------
// part of YACY
// (C) by Michael Peter Christen; mc@anomic.de
// first published on http://www.anomic.de
// Frankfurt, Germany, 2004
//
// This File is contributed by Jan Sandbrink
// Contains contributions from Marc Nause [MN]
// last change: 06.05.2006
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
// Using this software in any meaning (reading, learning, copying, compiling,
// running) means that you agree that the Author(s) is (are) not responsible
// for cost, loss of data or any harm that may be caused directly or indirectly
// by usage of this softare or this documentation. The usage of this software
// is on your own risk. The installation and usage (starting/running) of this
// software may allow other people or application to access your computer and
// any attached devices and is highly dependent on the configuration of the
// software which must be done by the user of the software; the author(s) is
// (are) also not responsible for proper configuration and usage of the
// software, even if provoked by documentation provided together with
// the software.
//
// Any changes to this file according to the GPL as documented in the file
// gpl.txt aside this file in the shipment you received can be done to the
// lines that follows this copyright notice here, but changes must not be
// done inside the copyright notive above. A re-distribution must contain
// the intact and unchanged copyright notice.
// Contributions and changes to the program code must be marked as such.

// You must compile this file with
// javac -classpath .:../classes Blog.java
// if the shell's current path is HTROOT

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;

import de.anomic.data.blogBoard;
import de.anomic.data.userDB;
import de.anomic.http.httpHeader;
import de.anomic.http.httpc;
import de.anomic.plasma.plasmaSwitchboard;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;
import de.anomic.server.logging.serverLog;
import de.anomic.yacy.yacyCore;
import de.anomic.yacy.yacyNewsPool;
import de.anomic.yacy.yacyNewsRecord;

public class Blog {

    private static final String DEFAULT_PAGE = "blog_default";
    
	private static SimpleDateFormat SimpleFormatter = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
//	TODO: make userdefined date/time-strings (localisation)
	
    public static String dateString(Date date) {
    	return SimpleFormatter.format(date);
    }
	
	public static serverObjects respond(httpHeader header, serverObjects post, serverSwitch env) {
		final plasmaSwitchboard switchboard = (plasmaSwitchboard) env;
		final serverObjects prop = new serverObjects();
		blogBoard.entry page = null;
		
        final boolean authenticated = switchboard.adminAuthenticated(header) >= 2;
        final int display = ((post == null) || (!authenticated)) ? 0 : post.getInt("display", 0);
        prop.put("display", display);
        
        boolean hasRights = switchboard.verifyAuthentication(header, true);
        final boolean xml = ((String)header.get(httpHeader.CONNECTION_PROP_PATH)).endsWith(".xml");
        final String address = yacyCore.seedDB.mySeed.getPublicAddress();

        if(hasRights) {
            prop.put("mode_admin",1);
        } else {
            prop.put("mode_admin",0);
        }
        
        if (post == null) {
            prop.put("peername", yacyCore.seedDB.mySeed.getName());
            prop.put("address", address);
            return putBlogDefault(prop, switchboard, address, 0, 20, hasRights, xml);
        }
        
        final int start = post.getInt("start",0); //indicates from where entries should be shown
        final int num   = post.getInt("num",20);  //indicates how many entries should be shown
        
        if(!hasRights){
            final userDB.Entry userentry = switchboard.userDB.proxyAuth((String)header.get("Authorization", "xxxxxx"));
            if(userentry != null && userentry.hasRight(userDB.Entry.BLOG_RIGHT)){
                hasRights=true;
            } else if(post.containsKey("login")) {
                //opens login window if login link is clicked - contrib [MN]
                prop.put("AUTHENTICATE","admin log-in");
            }
		}

		String pagename = post.get("page", DEFAULT_PAGE);
	    final String ip = (String)header.get(httpHeader.CONNECTION_PROP_CLIENTIP, "127.0.0.1");
	    
		String StrAuthor = post.get("author", "");
		
		if (StrAuthor.equals("anonymous")) {
			StrAuthor = switchboard.blogDB.guessAuthor(ip);
			
	    	if (StrAuthor == null || StrAuthor.length() == 0) {
	    		if (de.anomic.yacy.yacyCore.seedDB.mySeed == null)
	    			StrAuthor = "anonymous";
	        	else {
	        		StrAuthor = de.anomic.yacy.yacyCore.seedDB.mySeed.get("Name", "anonymous");
	        	}
	        }
	    }
		
		byte[] author;
		try {
			author = StrAuthor.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			author = StrAuthor.getBytes();
		}

		if(hasRights && post.containsKey("delete") && post.get("delete").equals("sure")) {
            page = switchboard.blogDB.read(pagename);
            final Iterator i = page.comments().iterator();
            while(i.hasNext()) {
                switchboard.blogCommentDB.delete((String) i.next());
            }
			switchboard.blogDB.delete(pagename);
			pagename = DEFAULT_PAGE;
		}
        
        if (post.containsKey("discard"))
            pagename = DEFAULT_PAGE;
        
        if (post.containsKey("submit") && (hasRights)) {
			// store a new/edited blog-entry
			byte[] content;
			try {
				content = post.get("content", "").getBytes("UTF-8");
			} catch (UnsupportedEncodingException e) {
				content = post.get("content", "").getBytes();
			}

			Date date = null;
            ArrayList comments = null;
			
			//set name for new entry or date for old entry
			if(pagename.equals(DEFAULT_PAGE))
				pagename = String.valueOf(System.currentTimeMillis());
			else {
				page = switchboard.blogDB.read(pagename);
                comments = page.comments();
				date = page.date();
			}
			final String commentMode = post.get("commentMode", "1");
			final String StrSubject = post.get("subject", "");
			byte[] subject;
			try {
				subject = StrSubject.getBytes("UTF-8");
			} catch (UnsupportedEncodingException e) {
				subject = StrSubject.getBytes();
			}
         
			switchboard.blogDB.write(switchboard.blogDB.newEntry(pagename, subject, author, ip, date, content, comments, commentMode));
            
			// create a news message
			final HashMap map = new HashMap();
			map.put("page", pagename);
			map.put("subject", StrSubject.replace(',', ' '));
			map.put("author", StrAuthor.replace(',', ' '));
			yacyCore.newsPool.publishMyNews(yacyNewsRecord.newRecord(yacyNewsPool.CATEGORY_BLOG_ADD, map));
		}

		page = switchboard.blogDB.read(pagename); //maybe "if(page == null)"

		if (post.containsKey("edit")) {
		    //edit an entry
			if(hasRights) {
				try {
                    prop.put("mode", 1); //edit
                    prop.put("mode_commentMode", page.getCommentMode());
			        prop.put("mode_author", new String(page.author(),"UTF-8"));
			        prop.put("mode_pageid", page.key());
			        prop.put("mode_subject", new String(page.subject(), "UTF-8"));
			        prop.put("mode_page-code", new String(page.page(), "UTF-8"));
			    } catch (UnsupportedEncodingException e) {}
			}
			else {
				prop.put("mode",3); //access denied (no rights)
			}
		}
		else if(post.containsKey("preview")) {
			//preview the page
			if(hasRights) {
	            prop.put("mode", 2);//preview
                prop.put("mode_commentMode", Integer.parseInt(post.get("commentMode", "1")));
	            prop.put("mode_pageid", pagename);
	            try {
					prop.put("mode_author", new String(author, "UTF-8"));
				} catch (UnsupportedEncodingException e) {
					prop.put("mode_author", new String(author));
				}
	            prop.put("mode_subject", post.get("subject",""));
	            prop.put("mode_date", dateString(new Date()));
	            prop.putWiki("mode_page", post.get("content", ""));
	            prop.put("mode_page-code", post.get("content", ""));
			}
			else prop.put("mode",3); //access denied (no rights)
		}
		else if(post.get("delete", "").equals("try")) {
			if(hasRights) {
				prop.put("mode",4);
				prop.put("mode_pageid",pagename);
				try {
					prop.put("mode_author",new String(page.author(), "UTF-8"));
				} catch (UnsupportedEncodingException e) {
					prop.put("mode_author",new String(page.author()));
				}
				try {
					prop.put("mode_subject",new String(page.subject(),"UTF-8"));
				} catch (UnsupportedEncodingException e) {
					prop.put("mode_subject",new String(page.subject()));
				}
			}
			else prop.put("mode",3); //access denied (no rights)
		}
		else if(post.containsKey("import")) {
			prop.put("mode",5);
			prop.put("mode_state",0);
		}
		else if(post.containsKey("xmlfile")) {
			prop.put("mode",5);
			if(switchboard.blogDB.importXML(new String((byte[])post.get("xmlfile$file")))) {
				prop.put("mode_state",1);
			}
			else {
				prop.put("mode_state",2);
			}
		}
		else {
		    // show blog-entry/entries
	        prop.put("mode", 0); //viewing
	        if(pagename.equals(DEFAULT_PAGE)) {
	            // XXX: where are "peername" and "address" used in the template?
                // XXX: "clientname" is already set to the peername, no need for a new setting
                prop.put("peername", yacyCore.seedDB.mySeed.getName());
                prop.put("address", address);
	        	//index all entries
                putBlogDefault(prop, switchboard, address, start, num, hasRights, xml);
	        }
	        else {
	        	//only show 1 entry
	        	prop.put("mode_entries",1);
                putBlogEntry(prop, page, address, 0, hasRights, xml);
	        }
		}

		// return rewrite properties
		return prop;
	}
    
    private static serverObjects putBlogDefault(
            final serverObjects prop,
            final plasmaSwitchboard switchboard,
            final String address,
            int start,
            int num,
            final boolean hasRights,
            final boolean xml) {
        try {
            final Iterator i = switchboard.blogDB.keys(false);
            String pageid;
            int count = 0; //counts how many entries are shown to the user
            if(xml) num = 0;
            final int nextstart = start+num;      //indicates the starting offset for next results
            while(i.hasNext() && (num == 0 || num > count)) {
                pageid = (String) i.next();
                if(0 < start--) continue;
                putBlogEntry(
                        prop,
                        switchboard.blogDB.read(pageid),
                        address,
                        count++,
                        hasRights,
                        xml);
            }
            prop.put("mode_entries",count);
            
            if(i.hasNext()) {
                prop.put("mode_moreentries",1); //more entries are availible
                prop.put("mode_moreentries_start",nextstart);
                prop.put("mode_moreentries_num",num);
            } else {
                prop.put("moreentries",0);
            }
        } catch (IOException e) { serverLog.logSevere("BLOG", "Error reading blog-DB", e); }
        return prop;
    }
    
    private static serverObjects putBlogEntry(
            final serverObjects prop,
            final blogBoard.entry entry,
            final String address,
            final int number,
            final boolean hasRights,
            final boolean xml) {
        
        // subject
        try {
            prop.put("mode_entries_" + number + "_subject", new String(entry.subject(),"UTF-8"));
        } catch (UnsupportedEncodingException e) {
            prop.put("mode_entries_" + number + "_subject", new String(entry.subject()));
        }
        
        // author
        try {
            prop.put("mode_entries_" + number + "_author", new String(entry.author(),"UTF-8"));
        } catch (UnsupportedEncodingException e) {
            prop.put("mode_entries_" + number + "_author", new String(entry.author()));
        }
        
        // comments
        if(entry.getCommentMode() == 0) {
            prop.put("mode_entries_" + number + "_commentsactive", 0);
        } else {
            prop.put("mode_entries_" + number + "_commentsactive", 1);
            prop.put("mode_entries_" + number + "_commentsactive_pageid", entry.key());
            prop.put("mode_entries_" + number + "_commentsactive_address", address);
            try {
                prop.put("mode_entries_" + number + "_commentsactive_comments", new String(entry.commentsSize(),"UTF-8"));
            } catch (UnsupportedEncodingException e) {
                prop.put("mode_entries_" + number + "_commentsactive_comments", new String(entry.commentsSize()));
            }
        }
        
        prop.put("mode_entries_" + number + "_date", dateString(entry.date()));
        prop.put("mode_entries_" + number + "_rfc822date", httpc.dateString(entry.date()));
        prop.put("mode_entries_" + number + "_pageid", entry.key());
        prop.put("mode_entries_" + number + "_address", address);
        prop.put("mode_entries_" + number + "_ip", entry.ip());
        
        if(xml) {
            prop.putASIS("mode_entries_" + number + "_page", entry.page());
            prop.put("mode_entries_" + number + "_timestamp", entry.timestamp());
        } else {
            prop.putWiki("mode_entries_" + number + "_page", entry.page());
        }
        
        if(hasRights) {
            prop.put("mode_entries_" + number + "_admin", 1);
            prop.put("mode_entries_" + number + "_admin_pageid",entry.key());
        } else {
            prop.put("mode_entries_" + number + "_admin", 0);
        }
        
        return prop;
    }
}
