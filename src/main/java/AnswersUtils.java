import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

class AnswersUtils {
    private MasterTree mt;
    private Connection connection;
    private String pollId;
    private String userId;

    AnswersUtils(Connection connection, String pollId, String userId) {
        this.connection = connection;
        this.pollId = pollId;
        this.userId = userId;
        this.mt = new MasterTree(connection);
    }

    AnswersUtils(Connection connection, String pollId) {
        this(connection, pollId, "");
    }

    void enterAnswersIntoDatabase(JsonArray answers) {
        // Go through head's adding changed vote values
        // (for first run all answers given)
        for (int i = 0; i < answers.size(); i++) {
            JsonObject answer = answers.get(i).getAsJsonObject();

            boolean vote = answer.get("support").getAsString().equals("yes");
            int id = answer.get("id").getAsInt();

            // So that we can safely ignore the dummy statement
            if (id < 10000) insertAnswer(vote, id);
        }
    }

    DynamicData resolveDynamicQuestions(JsonObject data) {
        // This list will have the "inconsistent" node at its head with all its
        // supporters/attackers in the rest of the list
        DynamicData dynamicData = new DynamicData(findDynamicQ(data));

        // If there are no dynamic questions we update the graph table
        if (dynamicData.isEnd()) {
            mt.updateVotes(pollId, userId);
            mt.updateScores(pollId);
            mt.deleteFromDataBase(pollId, userId);
        }

        return dynamicData;
    }

    void addUser() {
        try {
            PreparedStatement createUser = connection.prepareStatement(
                    "INSERT INTO ? (user_id)");
            createUser.setString(1, pollId + "_answers");
            PreparedStatement insertIp = connection.prepareStatement(
                    createUser.toString().replace("'", "\"") + " VALUES(?);");

            insertIp.setString(1, userId);
            insertIp.executeUpdate();
        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }
    }

    List<GraphData> getGraphData() {
        List<GraphData> graphData = new ArrayList<>();

        try {
            PreparedStatement getStatementData =
                    connection.prepareStatement(
                            "SELECT * FROM ? ORDER BY 'statement_id';");
            getStatementData.setString(1, pollId);

            getStatementData = connection.prepareStatement(
                    getStatementData.toString().replace("'", "\""));
            ResultSet statementData = getStatementData.executeQuery();

            while (statementData.next()) {
                String text = statementData.getString("statement");
                int id = statementData.getInt("statement_id");
                Integer parentId = statementData.getInt("parent_id");
                int yesVotes = statementData.getInt("yes_votes");
                int noVotes = statementData.getInt("no_votes");
                float score = statementData.getFloat("score");

                graphData.add(new GraphData(
                        id, parentId, score, yesVotes, noVotes, text));
            }

            return graphData;
        } catch (SQLException e) {
            System.out.println(e.getMessage() + " in getGraphData");
            return new ArrayList<>();
        }
    }

    private void insertAnswer(boolean vote, Integer id) {
        try {
            PreparedStatement insertAnswer =
                connection.prepareStatement("UPDATE ? SET ?=");
            insertAnswer.setString(1, pollId + "_answers");
            insertAnswer.setString(2, id.toString());

            PreparedStatement insertValues = connection.prepareStatement(
                    insertAnswer.toString().replace("'", "\"")
                    + "? WHERE user_id=?;");

            insertValues.setBoolean(1, vote);
            insertValues.setString(2, userId);

            insertValues.executeUpdate();
        } catch (SQLException e) {
            // This could be because we need to add the new column
            // so we should add that here.
            System.out.println(e.getMessage());
        }
    }

    // Turn all json arrays into arguments
    private List<Argument> convertToArgumentList(JsonArray arguments) {
        List<Argument> argList = new ArrayList<>();

        for (int i = 1; i < arguments.size(); i++) {
            JsonObject jsonArr = arguments.get(i).getAsJsonObject();
            Argument arg = new Argument(jsonArr);
            argList.add(arg);
        }

        return argList;
    }

    // Set the children of each argument using argList
    private void setChildrenArguments(List<Argument> argList) {
        for (int i = 0; i < argList.size(); i++) {
            Argument arg = argList.get(i);
            int argId = arg.getId();

            for (Argument currArg : argList) {
                if (argId == currArg.getParent()) {
                    arg.addChild(currArg);
                }
            }
        }
    }

    private List<Box> findDynamicQ(JsonObject data) {
        JsonArray arguments = data.get("questions").getAsJsonArray();
        JsonObject jsonHead = arguments.get(0).getAsJsonObject();

        // This needs to be set
        List<Argument> argList = convertToArgumentList(arguments);
        Argument head = new Argument(jsonHead);
        argList.add(head);

        setChildrenArguments(argList);

        List<Argument> inconsistencies = head.getInconsistencies();

        // If there are inconsistencies then store them with
        // their head node
        return inconsistencies
                .stream()
                .map(Argument::toBox)
                .collect(Collectors.toList());
    }
}
