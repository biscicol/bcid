package bcid;

import bcidExceptions.BCIDException;
import bcidExceptions.ForbiddenRequestException;
import bcidExceptions.ServerErrorException;
import bcidExceptions.UnauthorizedRequestException;
import ezid.EZIDService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.SettingsManager;

import javax.ws.rs.core.MultivaluedMap;
import java.sql.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;

/**
 * Mint new expeditions.  Includes the automatic creation of a core set of entity types
 */
public class expeditionMinter {
    protected Connection conn;
    public ArrayList<Integer> expeditionResources;
    private SettingsManager sm;
    private EZIDService ezidAccount;
    private String resolverTargetPrefix;
    private String resolverMetadataPrefix;
    database db;

    private static Logger logger = LoggerFactory.getLogger(expeditionMinter.class);

    /**
     * The constructor defines the class-level variables used when minting Expeditions.
     * It defines a generic set of entities (process, information content, objects, agents)
     * that can be used for any expedition.
     */
    public expeditionMinter() {
        db = new database();
        conn = db.getConn();

        // Initialize settings manager
        sm = SettingsManager.getInstance();
        sm.loadProperties();

        resolverTargetPrefix = sm.retrieveValue("resolverTargetPrefix");
        resolverMetadataPrefix = sm.retrieveValue("resolverMetadataPrefix");
    }

    public void close() {
        db.close();
    }

    /**
     * mint Expedition
     *
     * @param expedition_code
     * @param expedition_title
     * @param users_id
     *
     * @return
     */
    public Integer mint(
            String expedition_code,
            String expedition_title,
            Integer users_id,
            Integer project_id,
            Boolean isPublic) throws BCIDException {

        Integer expedition_id = null;

        //TODO this doesn't allow the HttpStatusCode to be correctly set should be a 403
        if (!userExistsInProject(users_id, project_id)) {
            throw new BCIDException("User ID " + users_id + " is not authorized to create datasets in this project");
        }

        /**
         *  Insert the values into the expeditions table
         */
        checkExpeditionCodeValid(expedition_code);
        if (!isExpeditionCodeAvailable(expedition_code, project_id)) {
            throw new BCIDException("Expedition Code already exists");
        }

        // Generate an internal ID to track this submission
        UUID internalID = UUID.randomUUID();

        // Use auto increment in database to assign the actual identifier.. this is threadsafe this way
        String insertString = "INSERT INTO expeditions " +
                "(internalID, expedition_code, expedition_title, users_id, project_id,public) " +
                "values (?,?,?,?,?,?)";
//            System.out.println("INSERT string " + insertString);
        PreparedStatement insertStatement = null;
        try{
            insertStatement = conn.prepareStatement(insertString);
            insertStatement.setString(1, internalID.toString());
            insertStatement.setString(2, expedition_code);
            insertStatement.setString(3, expedition_title);
            insertStatement.setInt(4, users_id);
            insertStatement.setInt(5, project_id);
            insertStatement.setBoolean(6, isPublic);

            insertStatement.execute();

            // Get the datasets_id that was assigned
            expedition_id = getExpeditionIdentifier(internalID);
        } catch (SQLException e) {
            throw new ServerErrorException(e);
        } finally {
            db.close(insertStatement, null);
        }
        return expedition_id;
    }


    /**
     * Attach an individual URI reference to a expedition
     *
     * @param expedition_code
     * @param bcid
     *
     */
    public void attachReferenceToExpedition(String expedition_code, String bcid, Integer project_id) {
        Integer expedition_id = getExpeditionIdentifier(expedition_code, project_id);
        resolver r = new resolver(bcid);
        Integer datasetsId = r.getDataGroupID();
        r.close();

        String insertString = "INSERT INTO expeditionsBCIDs " +
                "(expedition_id, datasets_id) " +
                "values (?,?)";

        PreparedStatement insertStatement = null;
        try {
            insertStatement = conn.prepareStatement(insertString);
            insertStatement.setInt(1, expedition_id);
            insertStatement.setInt(2, datasetsId);
            insertStatement.execute();
        } catch (SQLException e) {
            throw new ServerErrorException("Db error attaching Reference to Expedition", e);
        } finally {
            db.close(insertStatement, null);
        }
    }

    /**
     * Return the expedition identifier given the internalID
     *
     * @param datasetUUID
     *
     * @return
     *
     * @throws java.sql.SQLException
     */
    private Integer getExpeditionIdentifier(UUID datasetUUID) throws SQLException {
        String sql = "select expedition_id from expeditions where internalID = ?";
        PreparedStatement stmt = conn.prepareStatement(sql);
        stmt.setString(1, datasetUUID.toString());
        ResultSet rs = stmt.executeQuery();
        try {
            rs.next();
            return rs.getInt("expedition_id");
        } catch (SQLException e) {
            throw new ServerErrorException("Server Error", "SQLException while getting expedition Identifier", e);
        } finally {
            db.close(stmt, rs);
        }
    }

    private Integer getExpeditionIdentifier(String expedition_code, Integer project_id) {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            String sql = "SELECT expedition_id " +
                    "FROM expeditions " +
                    "WHERE expedition_code = ? AND " +
                    "project_id = ?";
            stmt = conn.prepareStatement(sql);

            stmt.setString(1, expedition_code);
            stmt.setInt(2, project_id);

            rs = stmt.executeQuery();
            rs.next();
            return rs.getInt("expedition_id");
        } catch (SQLException e) {
            throw new ServerErrorException("Db error while retrieving expeditionId",
                    "SQLException while retrieving expedition_id from expeditions table with expedition_code: " +
                    expedition_code + " and project_id: " + project_id, e);
        } finally {
            db.close(stmt, rs);
        }
    }

    /***
     *
     * @param expedition_code
     * @param ProjectId
     * @return
     */
    public Boolean expeditionExistsInProject(String expedition_code, Integer ProjectId) {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            String sql = "select expedition_id from expeditions " +
                    "where expedition_code = ? && " +
                    "project_id = ?";
            stmt = conn.prepareStatement(sql);

            stmt.setString(1, expedition_code);
            stmt.setInt(2, ProjectId);

            rs = stmt.executeQuery();
            if (rs.next()) return true;
        } catch (SQLException e) {
            throw new ServerErrorException(e);
        } finally {
            db.close(stmt, rs);
        }
        return false;
    }

    public String printMetadata(int id) throws SQLException {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            StringBuilder sb = new StringBuilder();
            String sql = "select expedition_id,expedition_code,expedition_title,username from expeditions,users where users.user_id = expeditions.users_id && expedition_id = ?";
            stmt = conn.prepareStatement(sql);

            stmt.setInt(1, id);

            rs = stmt.executeQuery();
            sb.append("***expedition***");

            // Get result set meta data
            ResultSetMetaData rsmd = rs.getMetaData();
            int numColumns = rsmd.getColumnCount();

            while (rs.next()) {
                // Loop mapped values, now we know the type
                for (int i = 1; i <= numColumns; i++) {
                    String val = rsmd.getColumnLabel(i);
                    sb.append("\n" + val + " = " + rs.getString(val));
                }
            }
            return sb.toString();
        } finally {
            db.close(stmt, rs);
        }
    }

    public String printMetadataHTML(int id) {
        StringBuilder sb = new StringBuilder();
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            String sql = "SELECT expedition_id,expedition_code,expedition_title,username " +
                    "FROM expeditions,users " +
                    "WHERE users.user_id = expeditions.users_id " +
                    "&& expedition_id = ?";
            stmt = conn.prepareStatement(sql);

            stmt.setInt(1, id);

            rs = stmt.executeQuery();
            sb.append("<table>");

            // Get result set meta data
            ResultSetMetaData rsmd = rs.getMetaData();
            int numColumns = rsmd.getColumnCount();

            while (rs.next()) {
                // Loop mapped values, now we know the type
                for (int i = 1; i <= numColumns; i++) {
                    String val = rsmd.getColumnLabel(i);
                    sb.append("<tr><td>" + val + "</td><td>" + rs.getString(val) + "</td></tr>");
                }
            }
            sb.append("</table>");
            return sb.toString();
        } catch (SQLException e) {
            throw new ServerErrorException("Db error retrieving expedition metadata", e);
        } finally {
            db.close(stmt, rs);
        }
    }


    /**
     * Discover if a user owns this expedition or not
     *
     * @param users_id
     * @param expedition_code
     *
     * @return
     */
    public boolean userOwnsExpedition(Integer users_id, String expedition_code, Integer project_id) {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            //String sql = "select expedition_id,expedition_code,expedition_title,username from expeditions,users where users.user_id = expeditions.users_id && users.username =\"" + remoteUser + "\"";

            String sql = "SELECT " +
                    "   count(*) as count " +
                    "FROM " +
                    "   expeditions " +
                    "WHERE " +
                    "   expedition_code= ? && " +
                    "   users_id = ? && " +
                    "   project_id = ?";
//            System.out.println(sql);
            stmt = conn.prepareStatement(sql);

            stmt.setString(1, expedition_code);
            stmt.setInt(2, users_id);
            stmt.setInt(3, project_id);

            rs = stmt.executeQuery();
            rs.next();
            if (rs.getInt("count") < 1)
                return false;
            else
                return true;
        } catch (SQLException e) {
            throw new ServerErrorException(e);
        } finally {
            db.close(stmt, rs);
        }
    }

    /**
     * Discover if a user belongs to an project
     *
     * @param users_id
     * @param project_id
     *
     * @return
     */
    public boolean userExistsInProject(Integer users_id, Integer project_id) {
        String selectString = "SELECT count(*) as count FROM usersProjects WHERE users_id = ? && project_id = ?";
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            stmt = conn.prepareStatement(selectString);

            stmt.setInt(1, users_id);
            stmt.setInt(2, project_id);

            rs = stmt.executeQuery();
            rs.next();
            return rs.getInt("count") >= 1;
        } catch (SQLException e) {
            throw new ServerErrorException(e);
        } finally {
            db.close(stmt, rs);
        }
    }

    /**
     * Generate a Deep Links Format data file for describing a set of root prefixes and associated concepts
     *
     * @param expedition_code
     *
     * @return
     */
    public String getDeepRoots(String expedition_code, Integer project_id) {
        // Get todays's date
        DateFormat dateFormat;
        dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        Date date = new Date();
        String expedition_title = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        StringBuilder sb = new StringBuilder();

        try {
            // Construct the query
            String sql =
                    "SELECT " +
                            " d.prefix as BCID, " +
                            " d.resourceType as resourceType," +
                            " d.title as alias, " +
                            " a.expedition_title as expedition_title " +
                            "FROM " +
                            " expeditions a, expeditionsBCIDs b, datasets d " +
                            "WHERE" +
                            " a.expedition_id = b.expedition_id && " +
                            " b.datasets_id = d.datasets_id && \n" +
                            " a.expedition_code = ? && \n" +
                            " a.project_id = ?";
            stmt = conn.prepareStatement(sql);

            stmt.setString(1, expedition_code);
            stmt.setInt(2, project_id);

            // Write the concept/prefix elements section
            sb.append("[\n{\n\t\"data\": [\n");
            rs = stmt.executeQuery();
            while (rs.next()) {
                // Grap the expedition_title in the query
                if (expedition_title == null & !rs.getString("expedition_title").equals(""))
                    expedition_title = rs.getString("expedition_title");

                // Grap the prefixes and concepts associated with this
                sb.append("\t\t{\n");
                sb.append("\t\t\t\"prefix\":\"" + rs.getString("BCID") + "\",\n");
                sb.append("\t\t\t\"concept\":\"" + rs.getString("resourceType") + "\",\n");
                sb.append("\t\t\t\"alias\":\"" + rs.getString("alias") + "\"\n");
                sb.append("\t\t}");
                if (!rs.isLast())
                    sb.append(",");

                sb.append("\n");
            }
        } catch (SQLException e) {
            throw new ServerErrorException(e);
        } finally {
            db.close(stmt, rs);
        }

        sb.append("\t]\n},\n");

        // Write the metadata section
        sb.append("{\n");
        sb.append("\t\"metadata\": {\n");
        sb.append("\t\t\"name\": \" " + expedition_code + "\",\n");
        if (expedition_title != null)
            sb.append("\t\t\"description\": \"" + expedition_title + "\",\n");
        sb.append("\t\t\"date\": \" " + dateFormat.format(date) + "\"\n");
        sb.append("\t}\n");
        sb.append("}\n");
        sb.append("]\n");
        return sb.toString();
    }

    /**
     * Generate a Deep Links Format data file for describing a set of root prefixes and associated concepts
     *
     * @param graphName
     *
     * @return
     */
    public String getGraphMetadata(String graphName) {
        // Get todays's date
        DateFormat dateFormat;
        dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        Date date = new Date();
        String expedition_title = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        StringBuilder sb = new StringBuilder();

        try {
            // Construct the query
            String sql =
                    "SELECT " +
                            " d.graph as graph, " +
                            " a.project_id as project_id, " +
                            " u.username as username_generator, " +
                            " u2.username as username_upload," +
                            " d.ts as timestamp," +
                            " d.prefix as BCID, " +
                            " d.resourceType as resourceType," +
                            " a.expedition_code as expedition_code, " +
                            " a.expedition_title as expedition_title, " +
                            " a.public as public " +
                            "FROM " +
                            " expeditions a, expeditionsBCIDs b, datasets d, users u, users u2 " +
                            "WHERE" +
                            " u2.user_id=d.users_id && " +
                            " u.user_id = a.users_id && " +
                            " a.expedition_id = b.expedition_id && " +
                            " b.datasets_id = d.datasets_id && \n" +
                            " d.graph = ?";
            stmt = conn.prepareStatement(sql);

            stmt.setString(1, graphName);
            //System.out.println(sql);
            // Write the concept/prefix elements section
            sb.append("{\n\t\"data\": [\n");
            rs = stmt.executeQuery();
            while (rs.next()) {
                // Grap the expedition_title in the query
                if (expedition_title == null & !rs.getString("expedition_title").equals(""))
                    expedition_title = rs.getString("expedition_title");

                // Grab the prefixes and concepts associated with this
                sb.append("\t\t{\n");
                sb.append("\t\t\t\"graph\":\"" + rs.getString("graph") + "\",\n");
                sb.append("\t\t\t\"project_id\":\"" + rs.getInt("project_id") + "\",\n");
                sb.append("\t\t\t\"username_generator\":\"" + rs.getString("username_generator") + "\",\n");
                sb.append("\t\t\t\"username_upload\":\"" + rs.getString("username_upload") + "\",\n");
                sb.append("\t\t\t\"timestamp\":\"" + rs.getString("timestamp") + "\",\n");
                sb.append("\t\t\t\"bcid\":\"" + rs.getString("BCID") + "\",\n");
                sb.append("\t\t\t\"resourceType\":\"" + rs.getString("resourceType") + "\",\n");
                sb.append("\t\t\t\"public\":\"" + rs.getBoolean("public") + "\",\n");
                sb.append("\t\t\t\"expedition_code\":\"" + rs.getString("expedition_code") + "\",\n");
                sb.append("\t\t\t\"expedition_title\":\"" + rs.getString("expedition_title") + "\"\n");

                sb.append("\t\t}");
                if (!rs.isLast())
                    sb.append(",");

                sb.append("\n");
            }
            sb.append("\t]\n}");
            return sb.toString();
        } catch (SQLException e) {
            throw new ServerErrorException(e);
        } finally {
            db.close(stmt, rs);
        }
    }

    public String expeditionTable(String remoteUser) {
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            StringBuilder sb = new StringBuilder();
            //String sql = "select expedition_id,expedition_code,expedition_title,username from expeditions,users where users.user_id = expeditions.users_id && users.username =\"" + remoteUser + "\"";

            String sql = "SELECT " +
                    "   a.expedition_id as expedition_id," +
                    "   a.expedition_code as expedition_code," +
                    "   a.expedition_title as expedition_title," +
                    "   d.prefix as BCID," +
                    "   d.resourceType as resourceType " +
                    "FROM " +
                    "   expeditions a,expeditionsBCIDs b,datasets d,users u " +
                    "WHERE " +
                    "   a.expedition_id=b.expedition_id && " +
                    "   b.datasets_id=d.datasets_id && " +
                    "   a.users_id = u.user_id && " +
                    "   u.username= ?";
            stmt = conn.prepareStatement(sql);

            stmt.setString(1, remoteUser);
            rs = stmt.executeQuery();

            // Get result set meta data

            sb.append("<table>\n");
            sb.append("\t<tr>\n");
            sb.append("\t\t<td><b>Expedition Details</b></td>\n");
            sb.append("\t\t<td><b>Expedition BCIDs</b></td>\n");
            sb.append("\t</tr>\n");

            Integer expedition_id = 0;
            Integer thisExpedition_id = 0;
            int count = 0;
            while (rs.next()) {

                thisExpedition_id = rs.getInt("expedition_id");

                // Structure the first column-- expeditions
                if (thisExpedition_id != expedition_id) {
                    if (count > 0) {
                        sb.append("\t\t\t</table>\n\t\t</td>\n");
                        sb.append("\t</tr>\n");
                    }

                    sb.append("\t<tr>\n");
                    sb.append("\t\t<td valign=top>\n");
                    sb.append("\t\t\t<table><tr><td>expeditionID " + rs.getString("expedition_id") + "</td></tr>" +
                            "<tr><td>" + rs.getString("expedition_code") + "</td></tr>" +
                            "<tr><td>" + rs.getString("expedition_title") + "</td></tr></table>\n");
                    sb.append("\t\t</td>\n");

                    sb.append("\t\t<td valign=top>\n\t\t\t<table>\n");
                } else {
                    //sb.append("\n\t\t<td></td>\n");
                }

                // Structure the second column-- BCIDs associated with expeditions
                ResourceTypes rt = new ResourceTypes();
                String rtString;
                ResourceType resourceType = rt.get(rs.getString("resourceType"));
                if (resourceType != null) {
                    rtString = "<a href='" + rs.getString("resourceType") + "'>" + resourceType.string + "</a>";
                } else {
                    rtString = "<a href='" + rs.getString("resourceType") + "'>" + rs.getString("resourceType") + "</a>";
                }


                sb.append("\t\t\t\t<tr><td><a href='" + resolverTargetPrefix + rs.getString("BCID") + "'>" +
                        rs.getString("BCID") + "</a></td>" +
                        "<td>is_a</td><td>" +
                        rtString +
                        "</td></tr>\n");

                // Close the BCID section tag
                if (thisExpedition_id != expedition_id) {
                    //if (count > 0) {
                    //    sb.append("\n\t\t\t</table>");
                    //    sb.append("\n\t\t</td>");
                    //}
                    expedition_id = thisExpedition_id;
                }
                count++;
                if (rs.isLast())
                    sb.append("\t\t\t</table>\n\t\t</td>\n");
            }

            sb.append("\t</tr>\n</table>\n");

            return sb.toString();
        } catch (SQLException e) {
            throw new ServerErrorException("Server Error","SQLException while retrieving expeditionTable for user: " + remoteUser, e);
        } finally {
            db.close(stmt, rs);
        }
    }


    public static void main(String args[]) {
        try {
            System.out.println("init ...");
            // See if the user owns this expedition or no
            expeditionMinter expedition = new expeditionMinter();
            //System.out.println(expedition.getGraphMetadata("_qNK_fuHVbRSTNvA_8pG.xlsx"));
           System.out.println("starting ...");
            System.out.println(expedition.listExpeditionsAsTable(9,"trizna"));
            System.out.println("ending ...");
            //System.out.println(expedition.listExpeditions(8,"mwangiwangui25@gmail.com"));
            //expedition.checkExpeditionCodeValid("JBD_foo-))");
            //    System.out.println("Configuration File for project = " +expedition.getValidationXML(1));
            /*
            if (expedition.expeditionExistsInProject("DEMOH", 1)) {
                System.out.println("expedition exists in project");
            } else {
                System.out.println("expedition does not exist in project");
            }
            */
            /*System.out.println(expedition.getDeepRoots("HDIM"));

            if (expedition.userOwnsExpedition(8, "DEMOG")) {
                System.out.println("YES the user owns this expedition");
            } else {
                System.out.println("NO the user does not own this expedition");
            }

*/
            // System.out.println(expedition.getLatestGraphsByExpedition(1));
            // Test associating a BCID to a expedition
            /*
            expedition.attachReferenceToExpedition("DEMOH", "ark:/21547/Fu2");
            */

            // Test creating a expedition
            /*
            Integer expedition_id = expedition.mint(
                    "DEMOH",
                    "Test creating expedition under an project for which it already exists",
                    8, 4, false);

            System.out.println(expedition.printMetadata(expedition_id));
            */

            //System.out.println(p.expeditionTable("demo"));
            expedition.close();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    /**
     * Check that expedition code is between 4 and 50 characters
     *
     * @param expedition_code
     *
     * @return
     */
    private void checkExpeditionCodeValid(String expedition_code) throws BCIDException {
        // Check expedition_code length
        if (expedition_code.length() < 4 || expedition_code.length() > 50) {
            // System.out.println("invalid length for dataset = " + expedition_code);
            throw new BCIDException("Dataset code " + expedition_code + " must be between 4 and 50 characters long");
        }

        // Check to make sure characters are normal!
        if (!expedition_code.matches("[a-zA-Z0-9_-]*")) {
            //System.out.println("invalid characters in dataset = " + expedition_code);
            throw new BCIDException("Dataset code " + expedition_code + " contains one or more invalid characters. " +
                    "Dataset code characters must be in one of the these ranges: [a-Z][0-9][-][_]");
        }
    }

    /**
     * Check that expedition code is not already in the database
     *
     * @param expedition_code
     *
     * @return
     */
    private boolean isExpeditionCodeAvailable(String expedition_code, Integer project_id) {
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            String sql = "SELECT count(*) as count " +
                    "FROM expeditions " +
                    "WHERE expedition_code = ? AND " +
                    "project_id = ?";
            stmt = conn.prepareStatement(sql);

            stmt.setString(1, expedition_code);
            stmt.setInt(2, project_id);

            rs = stmt.executeQuery();
            rs.next();
            Integer count = rs.getInt("count");
            if (count >= 1) {
                return false;
                //throw new Exception("Dataset code " + expedition_code + " already exists for this project.");
            }
            return true;
        } catch (SQLException e) {
            throw new ServerErrorException(e);
        } finally {
            db.close(stmt, rs);
        }

    }

    /**
     * Return a JSON response of the user's expeditions in a project
     *
     * @param projectId
     * @param username
     *
     * @return
     */
    public String listExpeditions(Integer projectId, String username) {
        StringBuilder sb = new StringBuilder();
        PreparedStatement stmt = null;
        ResultSet rs = null;

        sb.append("{\n");
        sb.append("\t\"expeditions\": [\n");
        Integer userId = db.getUserId(username);

        try {
            String sql = "SELECT expedition_id, expedition_title, expedition_code, public " +
                    "FROM expeditions " +
                    "WHERE project_id = ? && users_id = ?";
            //" and resourceType = \"http://purl.org/dc/dcmitype/Dataset\"\n";
            stmt = conn.prepareStatement(sql);

            stmt.setInt(1, projectId);
            stmt.setInt(2, userId);

            rs = stmt.executeQuery();
            while (rs.next()) {
                sb.append("\t\t{\n");
                sb.append("\t\t\t\"expedition_id\":\"" + rs.getString("expedition_id") + "\",\n");
                sb.append("\t\t\t\"expedition_code\":\"" + rs.getString("expedition_code") + "\",\n");
                sb.append("\t\t\t\"expedition_title\":\"" + rs.getString("expedition_title") + "\",\n");
                sb.append("\t\t\t\"public\":\"" + rs.getBoolean("public") + "\"\n");
                sb.append("\t\t}");
                if (!rs.isLast())
                    sb.append(",\n");
                else
                    sb.append("\n");
            }
        } catch (SQLException e) {
            throw new ServerErrorException(e);
        } finally {
//            db.close(stmt, rs);
        }

        sb.append("\t]\n}");

        return sb.toString();
    }

    /**
     * Return an HTML table of an expedition's resources
     *
     * @param expeditionId
     *
     * @return
     */
    public String listExpeditionResourcesAsTable(Integer expeditionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table>\n");
        sb.append("\t<tr>\n");
        sb.append("\t\t<th>BCID</th>\n");
        sb.append("\t\t<th>Resource Type</th>\n");
        sb.append("\t</tr>\n");

        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            String sql = "SELECT d.prefix, d.resourceType " +
                    "FROM datasets d, expeditionsBCIDs e " +
                    "WHERE d.datasets_id = e.datasets_id && e.expedition_id = ?";
            stmt = conn.prepareStatement(sql);

            stmt.setInt(1, expeditionId);

            ResourceTypes rt = new ResourceTypes();

            rs = stmt.executeQuery();
            while (rs.next()) {
                String rtString;
                ResourceType resourceType = rt.get(rs.getString("d.resourceType"));
                if (resourceType != null) {
                    rtString = resourceType.string;
                } else {
                    rtString = rs.getString("d.resourceType");
                }

                // if the resourceType is a dataset, don't add to table
                if (rtString.toLowerCase().contains("dataset")) {
                    continue;
                }

                sb.append("\t<tr>\n");
                sb.append("\t\t<td>");
                sb.append(rs.getString("d.prefix"));
                sb.append("</td>\n");
                sb.append("\t\t<td>");
                // only display a hyperlink if http: is specified under resource type
                if (rs.getString("d.resourceType").contains("http:")) {
                    sb.append("<a href=\"" + rs.getString("d.resourceType") + "\">" + rtString + "</a>");
                } else {
                    sb.append(rtString);
                }
                sb.append("</td>\n");
                sb.append("\t</tr>\n");
            }
        } catch (SQLException e) {
            throw new ServerErrorException(e);
        } finally {
            db.close(stmt, rs);
        }

        sb.append("</table>\n");
        return sb.toString();
    }

    /**
     * return an HTML table of an expedition's datasets
     *
     * @param expeditionId
     *
     * @return
     */
    public String listExpeditionDatasetsAsTable(Integer expeditionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table>\n");
        sb.append("\t<tr>\n");
        sb.append("\t\t<th>Web Address</th>\n");
        sb.append("\t\t<th>Timestamp</th>\n");
        sb.append("\t</tr>\n");

        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            String sql = "SELECT d.ts, d.webaddress, d.resourceType " +
                    "FROM datasets d, expeditionsBCIDs e " +
                    "WHERE d.datasets_id = e.datasets_id && e.expedition_id = ? " +
                    "ORDER BY d.ts DESC";
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, expeditionId);

            ResourceTypes rt = new ResourceTypes();

            rs = stmt.executeQuery();
            while (rs.next()) {
                String rtString;
                ResourceType resourceType = rt.get(rs.getString("d.resourceType"));
                if (resourceType != null) {
                    rtString = resourceType.string;
                } else {
                    rtString = rs.getString("d.resourceType");
                }

                // if the resourceType is a dataset, add it to the table
                if (rtString.toLowerCase().contains("dataset")) {

                    String webaddress = rs.getString("d.webaddress");

                    sb.append("\t<tr>\n");
                    sb.append("\t\t<td><a href=\"");
                    sb.append(webaddress);
                    sb.append("\">");
                    sb.append(webaddress);
                    sb.append("</a></td>\n");
                    sb.append("</td>\n");
                    sb.append("\t\t<td>");
                    sb.append(rs.getTimestamp("d.ts").toString());
                    sb.append("\t</tr>\n");
                }
            }
        } catch (SQLException e) {
            throw new ServerErrorException(e);
        } finally {
            db.close(stmt, rs);
        }

        sb.append("</table>\n");
        return sb.toString();
    }

    /**
     * Return an HTML Table of the expeditions associated with a project. Includes who owns the expedition,
     * the expedition title, and whether the expedition is public.  This information is returned as information
     * typically viewed by an Admin who wants to see details about what datasets are as part of an expedition
     *
     * @param projectId
     * @param username  the project's admins username
     *
     * @return
     */
    public String listExpeditionsAsTable(Integer projectId, String username) {
        StringBuilder sb = new StringBuilder();
        sb.append("<form method=\"POST\">\n");
        sb.append("<table>\n");
        sb.append("<tbody>\n");
        sb.append("\t<tr>\n");
        sb.append("\t\t<th>Username</th>\n");
        sb.append("\t\t<th>Expedition Title</th>\n");
        sb.append("\t\t<th>Public</th>\n");
        sb.append("\t</tr>\n");

        PreparedStatement stmt = null;
        ResultSet rs = null;

        projectMinter p = new projectMinter();
        try {
            Integer userId = db.getUserId(username);

            if (!p.userProjectAdmin(userId, projectId)) {
                throw new ForbiddenRequestException("You must be this project's admin to view its datasets.");
            }

            String sql = "SELECT max(d.datasets_id) datasets_id, e.expedition_title, e.expedition_id, e.public, u.username \n" +
                    " FROM expeditions as e, users as u, datasets d, expeditionsBCIDs pB \n" +
                    " WHERE \n" +
                    " \te.project_id = ? \n" +
                    " \tAND u.user_id = e.users_id \n" +
                    " \tAND d.datasets_id = pB.datasets_id \n" +
                    " \tAND pB.expedition_id = e.expedition_id \n" +
                    " \tAND d.resourceType = \"http://purl.org/dc/dcmitype/Dataset\" \n" +
                    " GROUP BY pB.expedition_id";
            /*
              "    \tfrom datasets d,expeditions p, expeditionsBCIDs pB\n" +
                "    \twhere pB.datasets_id=d.datasets_id\n" +
                "    \tand pB.expedition_id=p.expedition_id\n" +
                " and d.resourceType = \"http://purl.org/dc/dcmitype/Dataset\"\n" +
             */
            //System.out.println(sql);
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, projectId);

            rs = stmt.executeQuery();

            while (rs.next()) {
                sb.append("\t<tr>\n");
                sb.append("\t\t<td>");
                sb.append(rs.getString("u.username"));
                sb.append("</td>\n");
                sb.append("\t\t<td>");
                sb.append(rs.getString("e.expedition_title"));
                sb.append("</td>\n");
                sb.append("\t\t<td><input name=\"");
                sb.append(rs.getInt("e.expedition_id"));
                sb.append("\" type=\"checkbox\"");
                if (rs.getBoolean("e.public")) {
                    sb.append(" checked=\"checked\"");
                }
                sb.append("/></td>\n");
                sb.append("\t</tr>\n");
            }

            sb.append("\t<tr>\n");
            sb.append("\t\t<td></td>\n");
            sb.append("\t\t<td><input type=\"hidden\" name=\"project_id\" value=\"" + projectId + "\" /></td>\n");
            sb.append("\t\t<td><input id=\"expeditionForm\" type=\"button\" value=\"Submit\"></td>\n");
            sb.append("\t</tr>\n");

        } catch (SQLException e) {
            throw new ServerErrorException(e);
        } finally {
            p.close();
            db.close(stmt, rs);
        }

        sb.append("</tbody>\n");
        sb.append("</table>\n");
        sb.append("</form>\n");
        return sb.toString();
    }

    /**
     * Update the public status of a specific expedition
     */
    public Boolean updateExpeditionPublicStatus(String expeditionCode, Integer projectId, Boolean publicStatus) {
        PreparedStatement updateStatement = null;

        try {
            String updateString = "UPDATE expeditions SET public = ?" +
                    " WHERE expedition_code = \"" + expeditionCode + "\" AND project_id = " + projectId;

            updateStatement = conn.prepareStatement(updateString);
            updateStatement.setBoolean(1, publicStatus);

            updateStatement.execute();
            if (updateStatement.getUpdateCount() > 0)
                return true;
            else
                return false;

        } catch (SQLException e) {
            throw new ServerErrorException("Server Error", "SQLException while updating expedition public status.", e);
        } finally {
            db.close(updateStatement, null);
        }
    }

    /**
     * Update the public attribute of each expedition in the expeditions MultivaluedMap
     *
     * @param expeditions
     * @param projectId
     *
     * @return
     */
    public void updateExpeditionsPublicStatus(MultivaluedMap<String, String> expeditions, Integer projectId) {
        List<String> updateExpeditions = new ArrayList<String>();
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            String sql = "SELECT expedition_id, public FROM expeditions WHERE project_id = ?";
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, projectId);

            rs = stmt.executeQuery();

            while (rs.next()) {
                String expedition_id = rs.getString("expedition_id");
                if (expeditions.containsKey(expedition_id) &&
                        !expeditions.getFirst(expedition_id).equals(String.valueOf(rs.getBoolean("public")))) {
                    updateExpeditions.add(expedition_id);
                }
            }

            if (!updateExpeditions.isEmpty()) {
                String updateString = "UPDATE expeditions SET" +
                        " public = CASE WHEN public ='0' THEN '1' WHEN public = '1' THEN '0' END" +
                        " WHERE expedition_id IN (" + updateExpeditions.toString().replaceAll("[\\[\\]]", "") + ")";

//                System.out.print(updateString);
                db.close(stmt, null);
                stmt = conn.prepareStatement(updateString);

                stmt.executeUpdate();
            }

        } catch (SQLException e) {
            throw new ServerErrorException("Db error while updating Expeditions public status.", e);
        } finally {
            db.close(stmt, rs);
        }
    }
}
