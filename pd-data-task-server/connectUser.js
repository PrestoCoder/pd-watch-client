const formConnResult = (result, status) => {
    return {
        result: result,
        status: status
    };
};

const validateUser = async (userID, connection, table = "pd_watch_users") => {
    try {
        const [rows] = await connection.execute(`SELECT connected FROM ${table} WHERE user_id = ?`, [userID]);
        if (rows.length > 0) {
            console.log("User validation:", rows[0].connected !== undefined);
            return true;
        } else {
            console.log("User validation failed: User not found.");
            return false;
        }
    } catch (err) {
        console.error("Error validating user:", err);
        return false;
    }
};

exports.connectUser = async (userID, connection, table = "pd_watch_users") => {
    if (userID === "none") {
        return formConnResult(true, "userID is none for connection.");
    }
    
    const isValid = await validateUser(userID, connection);
    if (!isValid) {
        return formConnResult(false, `${userID} is invalid.`);
    }

    try {
        const [rows] = await connection.execute(`SELECT connected FROM ${table} WHERE user_id = ?`, [userID]);

        if (rows.length > 0 && rows[0].connected === 0) {
            await connection.execute(`UPDATE ${table} SET connected = 1 WHERE user_id = ?`, [userID]);
            return formConnResult(true, `${userID} connected.`);
        } else {
            return formConnResult(false, `User ${userID} is already connected to the app.`);
        }
    } catch (err) {
        console.error("Error connecting user:", err);
        return formConnResult(false, `Failed to connect user ${userID}.`);
    }
};

exports.disconnectUser = async (userID, connection, table = "pd_watch_users") => {
    if (userID === "none") {
        return formConnResult(true, "userID is none for disconnection.");
    }
    
    const isValid = await validateUser(userID, connection);
    if (!isValid) {
        return formConnResult(false, `${userID} is an invalid user.`);
    }

    try {
        const [rows] = await connection.execute(`SELECT connected FROM ${table} WHERE user_id = ?`, [userID]);
        if (rows.length > 0 && rows[0].connected === 1) {
            await connection.execute(`UPDATE ${table} SET connected = 0 WHERE user_id = ?`, [userID]);
            return formConnResult(true, `User ${userID} disconnected from the app.`);
        } else {
            return formConnResult(false, `User ${userID} is already disconnected from the app.`);
        }
    } catch (err) {
        console.error("Error disconnecting user:", err);
        return formConnResult(false, `Failed to disconnect user ${userID}.`);
    }
};
